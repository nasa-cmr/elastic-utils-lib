(defproject nasa-cmr/cmr-elastic-utils-lib "0.1.0-SNAPSHOT"
  :description "A library containing utilities for dealing with Elasticsearch."
  :url "***REMOVED***projects/CMR/repos/cmr/browse/elastic-utils-lib"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [nasa-cmr/cmr-common-lib "0.1.1-SNAPSHOT"]
                 [org.elasticsearch/elasticsearch "1.6.2" :exclusions [commons-codec]]
                 [clojurewerkz/elastisch "2.2.0-beta2" :exclusions [commons-codec]]

                 ;; Log4j needed to configure logging in elasticsearch.
                 ;; Version set to match elastic search numbers. Look in elasticsearch pom.xml
                 [log4j/log4j "1.2.17"]
                 [clj-http "2.0.0"]]

  :plugins [[lein-test-out "0.3.1"]]

  :profiles
  {:dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                        [org.clojars.gjahad/debug-repl "0.3.3"]]

         :source-paths ["src" "dev" "test"]}})


