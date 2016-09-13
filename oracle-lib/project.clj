(defproject nasa-cmr/cmr-oracle-lib "0.1.0-SNAPSHOT"
  :description "Contains utilities for connecting to and manipulating data in Oracle."
  :url "***REMOVED***projects/CMR/repos/cmr/browse/oracle-lib"

  ;; Need the maven repo for Oracle jars that aren't available in public maven repos.
  :repositories [["releases" "http://devrepo1.dev.echo.nasa.gov/data/dist/projects/echo/mavenrepo/"]]

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [nasa-cmr/cmr-common-lib "0.1.1-SNAPSHOT"]
                 [org.clojure/java.jdbc "0.4.2"]
                 [com.oracle/ojdbc6 "11.2.0.4"]
                 [com.oracle/ons "11.2.0.4"]
                 [com.oracle/ucp "11.2.0.4"]
                 [sqlingvo "0.7.15"]]

  :plugins [[test2junit "1.2.1"]]
  :jvm-opts ^:replace ["-server"
                       "-Dclojure.compiler.direct-linking=true"]
  :profiles
  {:dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                        [org.clojars.gjahad/debug-repl "0.3.3"]]
         :jvm-opts ^:replace ["-server"]
         :source-paths ["src" "dev" "test"]}}

  :aliases { ;; Alias to test2junit for consistency with lein-test-out
            "test-out" ["test2junit"]})
