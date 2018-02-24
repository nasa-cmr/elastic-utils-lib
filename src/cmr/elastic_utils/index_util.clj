(ns cmr.elastic-utils.index-util
  "Defines different types and functions for defining mappings"
  (:require [clojurewerkz.elastisch.rest.index :as esi]
            [cmr.elastic-utils.connect :as esc]
            [clojurewerkz.elastisch.rest.document :as doc]
            [cmr.common.log :as log :refer (debug info warn error)]
            [cmr.common.services.errors :as errors]
            [clj-time.format :as f]))

(defn date->elastic
  "Takes a clj-time date and returns it in a format suitable for indexing in elasticsearch."
  [date-time]
  (when date-time
    (f/unparse (f/formatters :date-time) date-time)))

(def string-field-mapping
  {:type "string" :index "not_analyzed"})

(def text-field-mapping
  "Used for analyzed text fields"
  {:type "string"
   ; these fields will be split into multiple terms using the analyzer
   :index "analyzed"
   ; Norms are metrics about fields that elastic can use to weigh certian fields more than
   ; others when computing a document relevance. A typical example is field length - short
   ; fields are weighted more heavily than long feilds. We don't need them for scoring.
   :omit_norms "true"
   ; split the text on whitespace, but don't do any stemmming, etc.
   :analyzer "whitespace"
   ; Don't bother storing term positions or term frequencies in this field
   :index_options "docs"})

(def date-field-mapping
  {:type "date" :format "yyyy-MM-dd'T'HH:mm:ssZ||yyyy-MM-dd'T'HH:mm:ss.SSSZ"})

(def double-field-mapping
  {:type "double"})

(def float-field-mapping
  {:type "float"})

(def int-field-mapping
  {:type "integer"})

(def bool-field-mapping
  {:type "boolean"
   ;; Cannot use doc values using Elasticsearch 1.6, however we can update the fielddata cache
   ;; at index time rather than on the first query. See
   ;; https://www.elastic.co/guide/en/elasticsearch/reference/1.6/fielddata-formats.html and
   ;; https://www.elastic.co/guide/en/elasticsearch/guide/1.x/preload-fielddata.html
   :fielddata {:loading "eager"}})

(defn stored
  "modifies a mapping to indicate that it should be stored"
  [field-mapping]
  (assoc field-mapping :store "yes"))

(defn not-indexed
  "modifies a mapping to indicate that it should not be indexed and thus is not searchable."
  [field-mapping]
  (assoc field-mapping :index "no"))

(defn doc-values
  "Modifies a mapping to indicate that it should use doc values instead of the field data cache
  for this field.  The tradeoff is slightly slower performance, but the field no longer takes
  up memory in the field data cache.  Only use doc values for fields which require a large
  amount of memory and are not frequently used for sorting."
  [field-mapping]
  (assoc field-mapping :doc_values true))

(defmacro defmapping
  "Defines a new mapping type for an elasticsearch index. The argument after the docstring
  can be used to specify additional top level maping properties.
  Example:
  (defmapping person-mapping :person
    \"Defines a person mapping.\"
    {:name string-field-mapping
     :age int-field-mapping})"
  ([mapping-name mapping-type docstring properties]
   `(defmapping ~mapping-name ~mapping-type ~docstring nil ~properties))
  ([mapping-name mapping-type docstring mapping-settings properties]
   `(def ~mapping-name
      ~docstring
      {~mapping-type
       (merge {:dynamic "strict"
               :_source {:enabled false}
               :_all {:enabled false}
               :_ttl {:enabled true}
               :properties ~properties}
              ~mapping-settings)})))

(defmacro defnestedmapping
  "Defines a new nested mapping type for an elasticsearch index. The argument after the
  docstring can be used to specify additional top level maping properties.
  Example:
  (defnestedmapping address-mapping
     \"Defines an address mapping.\"
     {:street string-field-mapping
     :city string-field-mapping})"
  ([mapping-name docstring properties]
   `(defnestedmapping ~mapping-name ~docstring nil ~properties))
  ([mapping-name docstring mapping-settings properties]
   `(def ~mapping-name
      ~docstring
      (merge ~mapping-settings
             {:type "nested"
              :dynamic "strict"
              :_source {:enabled false}
              :_all {:enabled false}
              :properties ~properties}))))

(defn create-index-or-update-mappings
  "Creates the index needed in Elasticsearch for data storage or updates it. Parameters are as follows:

  * index-name - the name of the index to use in elastic search.
  * index-settings - A map of index settings to pass to elastic search.
  * type-name - The name for the mapping
  * mappings - a map containing mapping details for field names and types as defined by defmapping.
  * elastic-store - A component containing an elastic connection under the :conn key"
  [index-name index-settings type-name mappings elastic-store]
  (let [conn (:conn elastic-store)]
    (if (esi/exists? conn index-name)
      (do
        (info (format "Updating %s mappings and settings" index-name))
        (let [response (esi/update-mapping conn index-name type-name :mapping mappings :ignore_conflicts false)]
          (when-not (= {:acknowledged true} response)
            (errors/internal-error!
             (str "Unexpected response when updating elastic mappings: " (pr-str response))))))
      (do
        (info (format "Creating %s index" index-name))
        (esi/create conn index-name :settings {:index index-settings} :mappings mappings)
        (esc/wait-for-healthy-elastic elastic-store)))
    (esi/refresh conn index-name)))

(defmacro try-elastic-operation
  "Handles any Elasticsearch exceptions from the body and converts them to internal errors. We do this
  because an ExceptionInfo exceptions in the CMR are considered CMR exceptions."
  [& body]
  `(try
     ~@body
     (catch clojure.lang.ExceptionInfo e#
       (errors/internal-error!
         (str "Call to Elasticsearch caught exception " (get-in (ex-data e#) [:object :body]))
         e#))))

(defn reset
  "Development time helper function to delete an index and recreate it to empty all data."
  [index-name index-settings type-name mappings elastic-store]
  (let [conn (:conn elastic-store)]
    (when (esi/exists? conn index-name)
      (info "Deleting the cubby index")
      (esi/delete conn index-name))
    (create-index-or-update-mappings index-name index-settings type-name mappings elastic-store)))

(defn save-elastic-doc
  "Save the document in Elasticsearch, raise error if failed.
  * elastic-store - A component containing an elastic connection under the :conn key
  * index-name - the name of the index to use in elastic search.
  * type-name - The name for the mapping
  * elastic-id - the identifier for the elastic document
  * doc - the elastic document to save.
  * version - the version of the document to use. This is usually revision-id of a concept. If the
  version of the document in elastic is newer than this then an exception will be raised. This can
  be overriden by passing :ignore-conflict? true in the options
  * options - Optional map of options. :ignore-conflict?, :refresh?, and :ttl (time to live) are options."
  ([elastic-store index-name type-name elastic-id doc version]
   (save-elastic-doc elastic-store index-name type-name elastic-id doc version nil))
  ([elastic-store index-name type-name elastic-id doc version options]
   (let [conn (:conn elastic-store)
         {:keys [ttl ignore-conflict? refresh?]} options
         elastic-options (merge {:version version :version_type "external_gte"}
                                (when ttl
                                  {:ttl ttl})
                                ;; Force refresh of the index when specified.
                                ;; NOTE: This has performance implications and should be used sparingly.
                                (when refresh?
                                  {:refresh "true"}))
         result (try-elastic-operation
                 (doc/put conn index-name type-name elastic-id doc elastic-options))]
     (if (:error result)
       (if (= 409 (:status result))
         (if ignore-conflict?
           (info (str "Ignore conflict: " (str result)))
           (errors/throw-service-error :conflict (str "Save to Elasticsearch failed " (str result))))
         (errors/internal-error! (str "Save to Elasticsearch failed " (str result))))))))

(defn delete-by-id
  "Delete a document from elastic by ID.
    Takes parameters:
      elastic-store - Connection to elastic
      index-name - string name of the index
      type-name - symbol of concept type to be deleted
      id - ID of document to be deleted (concept id)
    And Options
     :refresh? - to synchronously force the index to make the change searchable. Use with care.

    Returns a hashmap of the HTTP response"
  ([elastic-store index-name type-name id]
   (delete-by-id elastic-store index-name type-name id nil))
  ([elastic-store index-name type-name id options]
   (let [elastic-options (if (:refresh? options)
                           {:refresh "true"}
                           {})]
     (doc/delete (:conn elastic-store) index-name type-name id elastic-options))))

(defn delete-by-query
  "Delete document that match the given query"
  [elastic-store index-name type-name query]
  (doc/delete-by-query (:conn elastic-store) index-name type-name query))

(defn create-index-alias
  "Creates the alias for the index."
  [conn index alias]
  (esi/update-aliases conn [{:add {:index index :alias alias}}]))
