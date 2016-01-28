(defproject goad "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [ring "1.3.1"]
                 [scenic "0.2.1"]
                 [johncowie/thingies "0.1.3"]
                 [com.novemberain/monger "3.0.2"]
                 [clj-time "0.8.0"]
                 [environ "1.0.0"]
                 [enlive "1.1.5"]
                 [traversy "0.2.0"]
                 [bidi "1.10.5"]
                 [johncowie/middleware "0.1.1"]]
  :profiles {:dev {:dependencies [[midje "1.6.3"]]
                   :plugins [[lein-ring "0.8.11"]
                             [lein-midje "3.1.1"]]}}
  :main goad.core
  :aot :all
  :ring {:handler goad.core/app
         :reload-paths ["src" "resources"]}
  )
