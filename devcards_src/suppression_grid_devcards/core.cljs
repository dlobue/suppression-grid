(ns suppression-grid-devcards.core
  (:require [devcards.core :as dc :include-macros true]
            [om.core :as om :include-macros true]
            [cljs.core.async :refer [put!]]
            [goog.string :as gstring]
            [goog.string.format]
            [om-bootstrap.panel :as p]
            [om-bootstrap.input :as i]
            [om-bootstrap.random :as r]
            [om-bootstrap.button :as b]
            [om-tools.dom :as d :include-macros true]
            [om-tools.core :refer-macros [defcomponent defcomponentk defcomponentmethod]]
            [bootstrap-cljs :as bs :include-macros true]
            [suppression-grid.core :as sg])
  (:require-macros [devcards.core :refer [defcard is are= are-not=]]))

(enable-console-print!)

(devcards.core/start-devcard-ui!)
(devcards.core/start-figwheel-reloader!)

;; remember to run lein figwheel and then browse to
;; http://localhost:3449/devcards/index.html


(defn ack-entry-handler [state]
  (->> (:text @state)
      js/JSON.parse
      js->clj
      (sg/conjack! sg/ackdb-ref)))


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
    (put! sg/ingest-channel (new-event h s))))

(defn gen-events [args]
  (doseq [[FORMAT NUM SERVICES] args]
    (gen-events-for-host-type FORMAT NUM SERVICES)))





(defcomponentk doom-item [data owner state]
  (init-state [_] {:text ""})
  (render
   [_]
   (d/div
    (d/button {:on-click (fn [_] (gen-events gen-config))} "Generate events")
    (d/button {:on-click #(sg/get-ackdb data)} "Get ackdb")
    (d/br)

    (d/input
     {:feedback? true
      :type "text"
      :placeholder "ackdb entry"
      :on-change #(sg/handle-change owner state)})

    (d/button {:on-click #(ack-entry-handler state)} "Submit to ackdb"))))




(defcard doom-card
  (dc/om-root-card doom-item sg/app-state))


(defcomponentk config-test [[:data config]]
  (render
   [_]
   (d/div
    (sg/->test-modal config)
    (d/br)
    (sg/->config-modal config)
    (d/br)
    (sg/->config-form config))))

(defcard config-card
  (dc/om-root-card config-test sg/app-state))


(defcard new-app-root
  (dc/om-root-card sg/servers sg/app-state))
