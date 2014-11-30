(ns suppression-grid.router
  (:require [goog.events            :as events]
            [om.core                :as om :include-macros true]
            [goog.history.EventType :as EventType]
            [secretary.core         :refer [add-route! dispatch!]])
  (:import goog.History))

(defonce history (History.))

(defn init [routes app]
  (doseq [[route view] (partition 2 routes)]
    (add-route! route #(swap! app assoc :router {:view view :params %})))

  (goog.events/listen history EventType/NAVIGATE #(-> % .-token dispatch!))
  (.setEnabled history true))

(defn redirect [location]
  (.setToken history location))
