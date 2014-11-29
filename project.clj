(defproject suppression-grid "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2371"]
                 ;[org.clojure/clojurescript "0.0-2371" :scope "provided"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [secretary "1.2.0"]
                 [com.ninjudd/eventual "0.4.1"]
                 [racehub/om-bootstrap "0.3.1" :exclusions  [org.clojure/clojure]]
                 [om "0.8.0-alpha2"]
                 [cljs-http "0.1.20"]
                 [prismatic/om-tools "0.3.6"]
                 [prismatic/plumbing "0.3.5"]
                 [potemkin  "0.3.2"]
                 [riddley "0.1.9"]]

  :profiles { :dev {:dependencies [[devcards "0.1.2-SNAPSHOT"]
                                   [com.cemerick/piggieback "0.1.3"]
                                   [weasel "0.4.2"]]
                    :plugins [[lein-cljsbuild "1.0.3"]
                              [lein-figwheel "0.1.4-SNAPSHOT"]]
                    :source-paths ["src" "devcards_src"]}}

  :source-paths ["src"]

  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}

  :cljsbuild {
              :builds [{:id "devcards"
                        :source-paths ["devcards_src" "src"]
                        :compiler {
                                   :output-to "dev-resources/public/devcards/js/compiled/suppression_grid_devcards.js"
                                   :output-dir "dev-resources/public/devcards/js/compiled/out"
                                   :optimizations :none
                                   :pretty-print true
                                   :source-map true}}

                       {:id "app"
                          :source-paths ["src"]
                          :compiler {
                                     :output-to "resources/public/js/compiled/suppression_grid.js"
                                     :output-dir "resources/public/js/compiled/out"
                                     :optimizations :none
                                     :source-map true}}]}

  :figwheel { :css-dirs ["resources/public/css"] })
