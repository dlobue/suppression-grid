(ns suppression-grid.core
  (:require [devcards.core :as dc :include-macros true]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [clojure.data :as data]
            [clojure.string :as string]
            [cljs.core.async :refer [put! chan <! onto-chan to-chan]]
            [om-bootstrap.panel :as p]
            [om-bootstrap.input :as i]
            [om-bootstrap.random :as r]
            [om-bootstrap.button :as b]
            [bootstrap-cljs :as bs :include-macros true]
            [om-tools.dom :as d :include-macros true]
            [om-tools.core :refer-macros [defcomponent defcomponentk defcomponentmethod]]
            [ninjudd.eventual.client :refer [json-event-source close-event-source!]]
            [ankha.core :as ankha]
            [plumbing.core :refer-macros [for-map defnk]]
            [goog.string :as gstring]
            [goog.string.format]
            [cljs-http.client :as http]
            [cljs-http.util]
            [sablono.core :as sab :include-macros true])
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [devcards.core :refer [defcard]]))

(enable-console-print!)

(dc/start-devcard-ui!)
(dc/start-figwheel-reloader!)

(defonce ackdb (atom #{}))

(defonce event-states (atom (sorted-map
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
                                   )))


(defn conjack!
  "Add a member to the set of acknowledged host/service couples"
  [member]
  (swap! ackdb conj (vec member)))

(defn disjack!
  "Remove a member from the set of acknowledged host/service couples"
  [member]
  (swap! ackdb disj (vec member)))



(defn assoc-ack-state-with-event
  [{:keys [acked host service] :as event}]
  (assoc event :acked (contains? @ackdb [host service])))




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



(def ingest-channel (chan 10 (map assoc-ack-state-with-event)))

(def ingest_services
  ["ingest.com.rackspacecloud.blueflood.io.ElasticIO.Write Duration.p95"
   "ingest.com.rackspacecloud.blueflood.io.Instrumentation.Full Resolution Metrics Written.m1_rate"
   "ingest.com.rackspacecloud.blueflood.inputs.handlers.HttpMetricsIngestionHandler.HTTP Ingestion json processing timer.p95"
   "ingest.com.rackspacecloud.blueflood.inputs.handlers.HttpMetricsIngestionHandler.HTTP Ingestion persisting timer.p95"
   "ingest.com.rackspacecloud.blueflood.inputs.handlers.HttpMetricsIngestionHandler.HTTP Ingestion response sending timer.p95"])

(def db_services
  ["rollup.com.rackspacecloud.blueflood.service.RollupService.Rollup Execution Timer.p95"
   "rollup.com.rackspacecloud.blueflood.service.RollupService.Scheduled Slot Check"])

(def gen-config [[ "db%i.iad.prod.bf.k1k.me", 32, db_services], [ "app%i.iad.prod.bf.k1k.me", 2, ingest_services]])

(defn new-event [h s]
  {:host h
   :service s
   :metric (rand-int 9345093)
   :time (-> (js/Date.) .getTime (quot 1000))
   :state (rand-nth '("ok" "critical" "warning"))})

(defn gen-events-for-host-type [FORMAT NUM SERVICES]
  (doseq [h (map #(gstring/format FORMAT %) (range NUM))
          s SERVICES]
    (put! ingest-channel (new-event h s))))

(defn gen-events [args]
  (doseq [[FORMAT NUM SERVICES] args]
    (gen-events-for-host-type FORMAT NUM SERVICES)))



(defn get-ackdb [cursor]
  (go (let [{:keys [body] :as response} (<! (http/get "http://localhost:5559/acks"))
            new-ackdb (set body)
            to-deack (clojure.set/difference @ackdb new-ackdb)
            to-ack (clojure.set/difference new-ackdb @ackdb)]
        (doseq [[host service] to-deack]
          (if (= "maintenance-mode" service)
            (om/transact!
             cursor
             host
             #(assoc % service false))
            (om/transact!
             cursor
             [host :service-states service]
             #(assoc % :acked false))))
        (doseq [[host service] to-ack]
          (if (= "maintenance-mode" service)
            (om/transact!
             cursor
             host
             #(assoc % service true))
            (om/transact!
             cursor
             [host :service-states service]
             #(assoc % :acked true))))
        (reset! ackdb (set body)))))


(defn -ack-submit [host service]
  (http/post "http://localhost:5559/acks" {:json-params [host service]}))

(defn ack-update [http-fn cursor ack-item]
  (go (let [response (<! (http-fn "http://localhost:5559/acks"
                                    {:json-params ack-item}))]
        ;;TODO: handle error conditions? log errors?
        (when (http/unexceptional-status? (:status response))
          (conjack! ack-item)
          (om/update! cursor :acked true)))))

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
              (do
                (conjack! ack-item)
                #_(om/update! cursor :acked true))
              (do
                (disjack! ack-item)
                #_(om/update! cursor :acked false))))))))


(defn ack-submit [cursor]
  ;;TODO: make url configurable
  ;;TODO: this won't work for host-wide maint-mode
  ;;TODO: won't work for deleting either
  (go (let [{:keys [host service]} @cursor
            ack-item [host service]
            response (<! (http/post "http://localhost:5559/acks"
                                    {:json-params ack-item}))]
        ;;TODO: handle error conditions? log errors?
        (when (http/unexceptional-status? (:status response))
          (conjack! ack-item)
          (om/update! cursor :acked true)))))

(defn ack-entry-handler [state]
  (-> (:text @state)
      js/JSON.parse
      js->clj
      conjack!))


(defn handle-change
  "Grab the input element via the `input` reference."
  [owner state]
  (let [node (om/get-node owner "input")]
    (swap! state assoc :text (.-value node))))



(defcomponentk doom-item [data owner state]
  (init-state [_] {:text ""})
  (render
   [this]
   (d/li
    (d/button {:on-click (fn [_] (gen-events gen-config))} "Generate events")
    (d/button {:on-click #(get-ackdb data)} "Get ackdb")
    (d/button {:on-click #(http/post "http://localhost:5559/acks" {:json-params ["arrr!" "honk"]})} "send test to ackdb")
    (d/br)

    (i/input
     {:feedback? true
      :type "text"
      :placeholder "ackdb entry"
      :on-change #(handle-change owner state)})

    (d/button {:on-click #(ack-entry-handler state)} "Submit to ackdb"))))




(defcard doom-card
  (dc/om-root-card doom-item event-states))


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


#_(defmulti ack-widget
  (fn [cursor _]
    (if (:acked cursor)
      :is-acked
      :not-acked)))

#_(defcomponentmethod ack-widget :not-acked
  [cursor owner]
  (render
   [this]
   (b/button {:bs-size "xsmall"
              :class "pull-right"
              :on-click #(ack-submit cursor)} "Ack")))

#_(defcomponentmethod ack-widget :is-acked
  [cursor owner]
  (render
   [this]
   (b/button {:bs-size "xsmall"
              :bs-style "danger"
              :class "pull-right"} "Remove Ack")))


(defcomponentk ack-widget-orig [data owner [:shared host ack-service-name]]
  (render
   [_]
   (if (:acked data)
     (b/button {:bs-size "xsmall"
                :bs-style "danger"
                :class "pull-right"
                :on-click #(ack-dispatcher :del data host ack-service-name)}
               "Remove Ack")

     (b/button {:bs-size "xsmall"
                :class "pull-right"
                :on-click #(ack-dispatcher :add data host ack-service-name)}
               "Ack"))))


(defcomponentk ack-widget [data owner [:shared host ack-service-name]]
  (render
   [_]
   (if (contains? @ackdb [host ack-service-name])
     (b/button {:bs-size "xsmall"
                :bs-style "danger"
                :class "pull-right"
                :on-click #(ack-dispatcher :del data host ack-service-name)}
               "Remove Ack")

     (b/button {:bs-size "xsmall"
                :class "pull-right"
                :on-click #(ack-dispatcher :add data host ack-service-name)}
               "Ack"))))


(defcomponentk service [data owner [:shared host]]
  (render
   [this]
   (d/li {:class "list-group-item"}
         (gstring/format "servicename: %s metric: %s state: %s"
                         (:service data)
                         (:metric data)
                         (:state data))
         (om/build ack-widget data {:shared {:host host
                                               :ack-service-name (:service data)}}))))


#_(defcomponent service [[servicename cursor] owner]
  (render
   [this]
   (d/li {:class "list-group-item"}
         (gstring/format "servicename: %s metric: %s state: %s"
                         servicename
                         (:metric cursor)
                         (:state cursor))
         (om/build ack-widget cursor {:shared {:host :fuck-me
                                               :ack-service-name servicename}}))))



(defcomponent server [[hostname cursor] owner]
  (render
   [this]
   ;(.info js/console (str (-> (js/Date.) .getTime (quot 1000)) " rendering server"))
   (p/panel
    {:header (list hostname
                   (om/build ack-widget
                             cursor
                             #_{:acked (:acked cursor)}
                             #_(select-keys cursor [:acked])
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


(defcomponentk servers [data owner state]
  (init-state
   [_]
   {:text ""
    :sse-info nil
    :ingest-channel ingest-channel})
  (will-mount
   [_]
   (.info js/console (str (-> (js/Date.) .getTime (quot 1000)) " will-mount servers"))
   (let [{:keys [ingest-channel]} @state]
     (insert-cursor data ingest-channel)))
  (will-unmount
   [_]
   (when-let [sse-info (:sse-info @state)]
     (close-event-source! sse-info)))
  (render
   [this]
   ;(.info js/console (str (-> (js/Date.) .getTime (quot 1000)) " rendering servers"))
   (d/div
    (b/button {:on-click
               #(init-riemann-sse "http://localhost:5558/index?query=true" state)
               } "connect to riemann")
    (b/button {:on-click
               #(when-let [sse-info (:sse-info @state)]
                  (close-event-source! sse-info))
               } "disconnect from riemann")
    (i/input
     {:feedback? true
      :type "text"
      :placeholder "Filter"
      :on-change #(handle-change owner state)
      })
    (p/panel
     {:class "panel-group"}
     (om/build-all
      server
      (filter-servers data (:text @state))
      {:key 0
       :state {:text (:text @state)}})))))

#_(defcard atom-contents
  (dc/edn-card event-states))


(defcard ackdb-contents
  (dc/edn-card ackdb))

(defcard omcard-practice-state
  (dc/om-root-card servers event-states {:shared ackdb}))



















(defcomponentk ack-widget-bcljs [data owner [:shared host ack-service-name]]
  (render
   [_]
   (if (:acked data)
     (bs/button {:bs-size "xsmall"
                :bs-style "danger"
                :class "pull-right"
                :on-click #(ack-dispatcher :del data host ack-service-name)}
               "Remove Ack")

     (bs/button {:bs-size "xsmall"
                :class "pull-right"
                :on-click #(ack-dispatcher :add data host ack-service-name)}
               "Ack"))))


(defcomponentk service-bcljs [data owner [:shared host]]
  (render
   [this]
   (d/li {:class "list-group-item"}
         (gstring/format "servicename: %s metric: %s state: %s"
                         (:service data)
                         (:metric data)
                         (:state data))
         (om/build ack-widget-bcljs data {:shared {:host host
                                               :ack-service-name (:service data)}}))))




(defcomponent server-bcljs [[hostname cursor] owner]
  (render
   [this]
   ;(.info js/console (str (-> (js/Date.) .getTime (quot 1000)) " rendering server"))
   (p/panel
    {:header (list hostname
                   (om/build ack-widget-bcljs
                             cursor
                             #_{:acked (:acked cursor)}
                             #_(select-keys cursor [:acked])
                             {:shared {:host hostname
                                       :ack-service-name "maintenance-mode"}}))
     :list-group
     (d/ul
      {:class "list-group"}
      (om/build-all
       service-bcljs
       (:service-states cursor)
       #_(filter-services
        (:service-states cursor)
        (om/get-state owner :text))
       {:key :service
        :fn second
        :shared {:host hostname
                 :ack-service-name "maintenance-mode"}}))})))




(defcomponentk servers-bcljs [data owner state]
  (init-state
   [_]
   {:text ""
    :sse-info nil
    :ingest-channel ingest-channel})
  (will-mount
   [_]
   (.info js/console (str (-> (js/Date.) .getTime (quot 1000)) " will-mount servers"))
   (let [{:keys [ingest-channel]} @state]
     (insert-cursor data ingest-channel)))
  (will-unmount
   [_]
   (when-let [sse-info (:sse-info @state)]
     (close-event-source! sse-info)))
  (render
   [this]
   ;(.info js/console (str (-> (js/Date.) .getTime (quot 1000)) " rendering servers"))
   (d/div
    (bs/button {:on-click
               #(init-riemann-sse "http://localhost:5558/index?query=true" state)
               } "connect to riemann")
    (bs/button {:on-click
               #(when-let [sse-info (:sse-info @state)]
                  (close-event-source! sse-info))
               } "disconnect from riemann")
    (bs/input
     {:feedback? true
      :type "text"
      :placeholder "Filter"
      :on-change #(handle-change owner state)
      })
    (bs/panel-group
     ;{:class "panel-group"}
     (om/build-all
      server-bcljs
      (filter-servers data (:text @state))
      {:key 0
       :state {:text (:text @state)}})))))


(defcard bootstrap-cljs-compare
  (dc/om-root-card servers-bcljs event-states {:shared ackdb}))
