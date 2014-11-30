(ns suppression-grid.core
  (:require [om.core :as om :include-macros true]
            [plumbing.core :refer-macros [defnk fnk for-map]]
            [figwheel.client :as fw :include-macros true]
            [cljs.core.async :refer [put! chan <! onto-chan to-chan timeout]]
            [secretary.core :as secretary :refer-macros [defroute]]
            [om-tools.dom :as d :include-macros true]
            [om-tools.core :refer-macros [defcomponent defcomponentk]]
            [ninjudd.eventual.client :refer [json-event-source close-event-source!]]
            [goog.string :as gstring]
            [goog.string.format]
            [bootstrap-cljs :as bs :include-macros true]
            [suppression-grid.router :as router]
            [cljs-http.client :as http]
            [cljs-http.util :refer [build-url]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(enable-console-print!)
(fw/watch-and-reload
 :jsload-callback (fn [] (print "reloaded")))

(defonce app-state (atom {:ackdb {}
                          :router {}
                          :config {:riemann-sse-url "http://localhost:5558/index"
                                   :riemann-sse-query "true"
                                   :suppression-poll-interval nil
                                   :suppression-url "http://localhost:5559/acks"}
                          :server-states (sorted-map
                                          "HOSTNAME-A"
                                          {:service-states
                                           (sorted-map
                                            "SERVICE-A"
                                            {:state "ok"
                                             :host "HOSTNAME-A"
                                             :service "SERVICE-A"
                                             :metric 123}
                                            "SERVICE-B"
                                            {:state "critical"
                                             :host "HOSTNAME-A"
                                             :service "SERVICE-B"
                                             :metric 333})
                                           }
                                          "HOSTNAME-C"
                                          {
                                           :service-states
                                           (sorted-map
                                            "SERVICE-D"
                                            {:state "warning"
                                             :host "HOSTNAME-C"
                                             :service "SERVICE-D"
                                             :metric 723}
                                            "SERVICE-F"
                                            {:state "critical"
                                             :host "HOSTNAME-C"
                                             :service "SERVICE-F"
                                             :metric 333})}
                                          )}))



(defn config->req [{:keys [riemann-sse-url riemann-sse-query]
                    :or {riemann-sse-query "true"}}]
  {:url riemann-sse-url
   :query-params {:query riemann-sse-query}})


(defn config-ref [] (om/ref-cursor (:config (om/root-cursor app-state))))
(defn ackdb-ref [] (om/ref-cursor (:ackdb (om/root-cursor app-state))))

(defn conjack!
  "Add a member to the set of acknowledged host/service couples"
  ;; ([member]
  ;;    (swap! ackdb conj (vec member)))
  ([cursor member]
     (conjack! cursor nil member))
  ([cursor korks member]
     (om/transact! cursor korks #(assoc % (vec member) nil))
     ;(om/transact! cursor #(conj % (vec member)))
     ))

(defn disjack!
  "Remove a member from the set of acknowledged host/service couples"
  ;; ([member]
  ;;    (swap! ackdb disj (vec member)))
  ([cursor member]
     (disjack! cursor nil member))
  ([cursor korks member]
     (om/transact! cursor korks #(dissoc % (vec member)))
     ;(om/transact! cursor #(disj % (vec member)))
     ))


(defn wrap-build-url [client]
  (fn [request]
    (if-not (string? request)
      (client (build-url request))
      (client request))))


(defn wrap-client
  [client]
  (-> client
      wrap-build-url
      http/wrap-query-params
      http/wrap-url))

(def wrapped-sse-client
  (wrap-client json-event-source))


(defn update-cursor [{:keys [state service host] :as n} cursor]
  (if (= "ok" state)
    (when-let [service-states (get-in @cursor [host :service-states])]
      (if (and (= 1 (count service-states))
               (contains? service-states service))
        (om/transact!
         cursor
         #(dissoc % host))
        (om/transact!
         cursor
         [host :service-states]
         #(dissoc % service))))
    (om/transact!
     cursor
     [host :service-states service]
     #(merge % n))))

(defn insert-cursor [cursor ch]
  (go (loop []
        (when-let [ne (<! ch)]
          (update-cursor ne cursor)
          (recur)))))

(defn get-now []
  (-> (js/Date.) .getTime (quot 1000)))

(defn riemann-sse-middleman [in-ch out-ch]
  (go (loop [mark (get-now) buf nil]
        (when-let [e (<! in-ch)]
          (let [buf (-> e
                        (js->clj :keywordize-keys true)
                        (cons buf))
                now (get-now)
                go-time? (> (- now mark) 5)]
            (when go-time?
              (onto-chan out-ch buf false)
              (recur now nil))
            (recur mark buf))))))


(defn init-riemann-sse [sse-config state]
  (when-let [sse-info (get @state :sse-info)]
    (close-event-source! sse-info))
  (let [{:keys [channel] :as sse-info} (wrapped-sse-client
                                        (config->req @sse-config))
        {:keys [ingest-channel]} @state]
    (swap! state assoc :sse-info sse-info)
    (riemann-sse-middleman channel ingest-channel)))

(def ingest-channel (chan))



(defn poll-ackdb [cursor]
  (let [poll-interval (-> @cursor
                          (get-in [:config :suppression-poll-interval])
                          js/parseInt
                          (* 1000))
        poll-interval (when (and (number? poll-interval)
                                 (not (js/isNaN poll-interval))
                                 (> 1000 poll-interval))
                        poll-interval)]
    (go-loop []
      (let [{:keys [body] :as response}
            (<! (http/get (get-in @cursor [:config :suppression-url])))]
        (om/update! cursor :ackdb
                    ;; TODO: create SetCursor so we don't have cludge
                    ;;(set body)
                    (zipmap body (repeat nil)))
        (when poll-interval
          (<! (timeout poll-interval))
          (recur))))))



(defn get-ackdb [cursor]
  (go (let [{:keys [body] :as response}
            (<! (http/get (get-in @cursor [:config :suppression-url])))]
        (om/update! cursor :ackdb
                    ;;(set body)
                    (zipmap body (repeat nil))))))

(defn ack-dispatcher [action cursor host ack-service-name]
  (let [http-fn (case action
                  :add http/post
                  :del http/put
                  (throw (ex-info "Invalid action to dispatch to acknowledge server"
                                  {:type :invalid-action
                                   :action action})))
        ack-item [host ack-service-name]]
    (when (some nil? ack-item)
      (throw (ex-info "Host and service must not be nil!"
                      {:type :bad-data :data ack-item})))
    (go (let [response (<! (http-fn (:suppression-url @(config-ref))
                                    {:json-params ack-item}))]

          ;;TODO: handle error conditions? log errors?
          (when (http/unexceptional-status? (:status response))
            (if (= action :add)
              (conjack! cursor ack-item)
              (disjack! cursor ack-item)))))))


(defn handle-change
  "Grab the input element via the `input` reference."
  [owner state]
  (let [node (om/get-node owner "input")]
    (swap! state assoc :text (.-value node))))


(defn has-substr [s sub-s]
  (not (neg? (.indexOf s sub-s))))

(defn match-server-or-service [match host {:keys [service-states]}]
  (if (or (nil? match)
          (zero? (count match)))
    true
    (or (has-substr host match)
        (some #(has-substr % match)
              (keys service-states)))))

(defn filter-servers [data match]
  (into
   (sorted-map)
   (filter
    (fn [[host values]]
      (match-server-or-service match host values))
    data)))

(defn filter-services [data match]
  (into
   (sorted-map)
   (filter
    (fn [[service _]]
      (if (or (nil? match)
              (zero? (count match)))
        true
        (has-substr service match)))
    data)))


(defcomponentk config-modal [data owner]
  (init-state
   [_]
   {:riemann-sse-url (:riemann-sse-url @data)
    :riemann-sse-query (:riemann-sse-query @data)
    :suppression-url (:suppression-url @data)
    :suppression-poll-interval (:suppression-poll-interval @data)
    :visible? false})
  (render-state
   [_ {:keys [visible?] :as state}]
   (d/div {:class "pull-right"}
          (bs/button {:on-click #(om/set-state! owner :visible? true)}
                     (bs/glyphicon {:glyph "cog"}))
          (when visible?
            (bs/modal {:title "Configure"
                       :animated false
                       :on-request-hide #(om/set-state! owner :visible? false)}
                      (let [input-helper (fnk [key label help]
                                              (bs/input {:type "text"
                                                         :label label
                                                         :feedback? true
                                                         :addon-after
                                                         (bs/overlay-trigger
                                                          {:placement "top"
                                                           :overlay
                                                           (bs/tooltip help)}
                                                          (bs/glyphicon {:glyph "question-sign"}))
                                                         :value (get state key)
                                                         :on-change #(om/set-state!
                                                                      owner
                                                                      key
                                                                      (.. % -target -value))}))]
                        (bs/accordion {:class "modal-body"
                                       :default-active-key "riemann"}
                                      (bs/panel {:header "Riemann SSE"
                                                 :key "riemann"}
                                                (input-helper
                                                 {:key :riemann-sse-url
                                                  :label "Url"
                                                  :help "Riemann SSE endpoint url"})
                                                (input-helper
                                                 {:key :riemann-sse-query
                                                  :label "query"
                                                  :help "Riemann Event filter"}))
                                      (bs/panel {:header "Suppressions"
                                                 :key "suppressions"}
                                                (input-helper
                                                 {:key :suppression-url
                                                  :label "Url"
                                                  :help "Suppressions endpoint url"})
                                                (input-helper
                                                 {:key :suppression-poll-interval
                                                  :label "Poll Interval"
                                                  :help "Interval to poll suppression endpoint for updates in seconds"}))))
                      (d/div {:class "modal-footer"}
                             (bs/button {:on-click
                                         (fn [_]
                                           (om/update-state!
                                            owner
                                            #(assoc @data
                                               :visible? false)))}
                                        "Close")
                             (bs/button {:bs-style "primary"
                                         :on-click #(do
                                                      (om/transact!
                                                       data
                                                       (fn [_]
                                                         (dissoc state :visible?)))
                                                      (om/set-state! owner :visible? false))
                                         }
                                        "Done")))))))

(defcomponentk ack-widget [data owner [:shared host ack-service-name]]
  (render
   [_]
   (let [acks (om/observe owner (ackdb-ref))]
     (if (contains? acks [host ack-service-name])
       (bs/button {:bs-size "xsmall"
                   :bs-style "danger"
                   :class "pull-right"
                   :on-click #(ack-dispatcher :del acks host ack-service-name)}
                  "Remove Ack")

       (bs/button {:bs-size "xsmall"
                   :class "pull-right"
                   :on-click #(ack-dispatcher :add acks host ack-service-name)}
                  "Ack")))))

(defcomponentk service [[:data service metric state] [:shared host]]
  (render
   [_]
   (bs/list-group-item
         (gstring/format
          "servicename: %s metric: %s state: %s"
          service metric state)
         (->ack-widget {}
                   {:shared {:host host
                             :ack-service-name service}}))))

(defcomponent server [[hostname cursor] owner]
  (render
   [this]
   (bs/panel
    {:header (d/span
              (d/a {:href (str "#/server/" hostname)}
                   hostname)
              (->ack-widget
               {}
               {:shared {:host hostname
                         :ack-service-name "maintenance-mode"}}))}
    (bs/list-group
     (om/build-all
      service
      (:service-states cursor)
      #_(filter-services
         (:service-states cursor)
         (om/get-state owner :text))
      {:key :service
       :fn second
       :shared {:host hostname
                :ack-service-name "maintenance-mode"}})))))

(defcomponent server-solo [data owner]
  (render
   [_]
   (let [hostname (get-in data [:router :params :name])
         cursor (get-in data [:server-states hostname])]
     (->server [hostname cursor]))))


(defcomponentk servers [[:data server-states config :as data] owner state]
  (init-state
   [_]
   {:text ""
    :sse-info nil
    :ingest-channel ingest-channel})
  (will-mount
   [_]
   (let [{:keys [ingest-channel]} @state]
     (insert-cursor server-states ingest-channel)))
  (will-unmount
   [_]
   (when-let [sse-info (:sse-info @state)]
     (close-event-source! sse-info)))
  (render
   [_]
   (d/div
    (bs/button-group
     (bs/button {:on-click
                #(init-riemann-sse config state)}
               "connect to riemann")
     (bs/button {:on-click #(when-let [sse-info (:sse-info @state)]
                             (close-event-source! sse-info))}
               "disconnect from riemann")
     (bs/button {:on-click #(get-ackdb data)} "refresh ackdb"))
    (->config-modal config)
    (bs/input
     {:feedback? true
      :type "text"
      :ref "input"
      :placeholder "Filter"
      :on-change #(om/set-state!
                   owner
                   :text
                   (.. % -target -value))})
    (bs/panel-group
     (om/build-all
      server
      (filter-servers server-states (:text @state))
      {:key 0
       :state {:text (:text @state)}})))))


(def routes ["/" servers
             "/server/:name" server-solo])

(when-let [app-element (.getElementById js/document "app")]
  (om/root (router/init routes app-state) app-state {:target app-element}))
