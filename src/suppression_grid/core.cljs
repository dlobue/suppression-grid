(ns suppression-grid.core
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cljs.core.async :refer [put! chan <! onto-chan to-chan]]
            [om-bootstrap.panel :as p]
            [om-bootstrap.input :as i]
            [om-bootstrap.random :as r]
            [om-bootstrap.button :as b]
            [om-tools.dom :as d :include-macros true]
            [om-tools.core :refer-macros [defcomponent defcomponentk]]
            [ninjudd.eventual.client :refer [json-event-source close-event-source!]]
            [goog.string :as gstring]
            [goog.string.format]
            [cljs-http.client :as http])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(enable-console-print!)

(defonce app-state (atom {:ackdb {}
                          :server-states (sorted-map
                                          "HOSTNAME-A"
                                          {:maintenance-mode false
                                           :service-states
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
                                          {:maintenance-mode false
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

(defn ackdb-ref [] (om/ref-cursor (:ackdb (om/root-cursor app-state))))

(defn conjack!
  "Add a member to the set of acknowledged host/service couples"
  ;; ([member]
  ;;    (swap! ackdb conj (vec member)))
  ([cursor member]
     (om/transact! cursor #(assoc % (vec member) nil))
     ;(om/transact! cursor #(conj % (vec member)))
     ))

(defn disjack!
  "Remove a member from the set of acknowledged host/service couples"
  ;; ([member]
  ;;    (swap! ackdb disj (vec member)))
  ([cursor member]
     (om/transact! cursor #(dissoc % (vec member)))
     ;(om/transact! cursor #(disj % (vec member)))
     ))


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


(defn init-riemann-sse [url state]
  (when-let [sse-info (get @state :sse-info)]
    (close-event-source! sse-info))
  (let [{:keys [channel] :as sse-info} (json-event-source url)
        {:keys [ingest-channel]} @state]
    (swap! state assoc :sse-info sse-info)
    (riemann-sse-middleman channel ingest-channel)))

(def ingest-channel (chan))

(defn get-ackdb [cursor]
  (go (let [{:keys [body] :as response}
            (<! (http/get "http://localhost:5559/acks"))]
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
    (go (let [response (<! (http-fn "http://localhost:5559/acks"
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

(defcomponentk ack-widget [data owner [:shared host ack-service-name]]
  (render
   [_]
   (let [acks (om/observe owner (ackdb-ref))]
     (if (contains? acks [host ack-service-name])
       (b/button {:bs-size "xsmall"
                  :bs-style "danger"
                  :class "pull-right"
                  :on-click #(ack-dispatcher :del acks host ack-service-name)}
                 "Remove Ack")

       (b/button {:bs-size "xsmall"
                  :class "pull-right"
                  :on-click #(ack-dispatcher :add acks host ack-service-name)}
                 "Ack")))))


(defcomponentk service [[:data service metric state] [:shared host]]
  (render
   [_]
   (d/li {:class "list-group-item"}
         (gstring/format
          "servicename: %s metric: %s state: %s"
          service metric state)
         (->ack-widget {}
                   {:shared {:host host
                             :ack-service-name service}}))))


(defcomponent server [[hostname cursor] owner]
  (render
   [this]
   ;(.info js/console (str (-> (js/Date.) .getTime (quot 1000)) " rendering server"))
   (p/panel
    {:header (list hostname
                   (->ack-widget
                    {}
                    {:shared {:host hostname
                              :ack-service-name "maintenance-mode"}}))
     :list-group
     (d/ul
      {:class "list-group"}
      (om/build-all
       service
       (:service-states cursor)
       #_(filter-services
          (:service-states cursor)
          (om/get-state owner :text))
       {:key :service
        :fn second
        :shared {:host hostname
                 :ack-service-name "maintenance-mode"}}))})))

(defcomponent hard-to-fail [cursor owner]
  (render
   [this]
   (d/li {:class "list-group-item"} (str cursor))))


(defcomponentk servers [[:data server-states :as data] owner state]
  (init-state
   [_]
   {:text ""
    :sse-info nil
    :ingest-channel ingest-channel})
  (will-mount
   [_]
   (.info js/console (str (-> (js/Date.) .getTime (quot 1000)) " will-mount servers"))
   (let [{:keys [ingest-channel]} @state]
     (insert-cursor server-states ingest-channel)))
  (will-unmount
   [_]
   (when-let [sse-info (:sse-info @state)]
     (close-event-source! sse-info)))
  (render
   [_]
   ;(.info js/console (str (-> (js/Date.) .getTime (quot 1000)) " rendering servers"))
   (d/div
    (b/button {:on-click
               #(init-riemann-sse "http://localhost:5558/index?query=true" state)}
              "connect to riemann")
    (b/button {:on-click #(when-let [sse-info (:sse-info @state)]
                            (close-event-source! sse-info))}
              "disconnect from riemann")
    (b/button {:on-click #(get-ackdb data)} "refresh ackdb")
    (i/input
     {:feedback? true
      :type "text"
      :placeholder "Filter"
      :on-change #(handle-change owner state)})
    (p/panel
     {:class "panel-group"}
     (om/build-all
      server
      (filter-servers server-states (:text @state))
      {:key 0
       :state {:text (:text @state)}})))))

(om/root servers app-state
  {:target (.getElementById js/document "app")})
