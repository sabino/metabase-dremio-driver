(ns metabase.driver.dremio
  "Dremio Driver."
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [java-time :as t]
            [metabase.config.core :as config]
            [metabase.driver :as driver]
            [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
            [metabase.driver.sql-jdbc.execute.legacy-impl :as legacy]
            [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
            [metabase.driver.sql-jdbc.sync.describe-database :as sync.describe-database]
            [metabase.driver.sql-jdbc.sync.interface :as sync.i]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.legacy-mbql.util :as mbql.u]
            [metabase.query-processor.store :as qp.store]
            [metabase.query-processor.util :as qputil]
            [metabase.util.honey-sql-2 :as h2x]
            [metabase.util.i18n :refer [trs]])
  (:import [java.sql Types]
           [java.time OffsetDateTime OffsetTime ZonedDateTime]))

(driver/register! :dremio, :parent #{:postgres ::legacy/use-legacy-classes-for-read-and-set})

(doseq [[feature supported?] {:table-privileges                false
                              :set-timezone                    false
                              :connection-impersonation        false
                              :describe-fks                    false
                              :metadata/key-constraints        true}]
  (defmethod driver/database-supports? [:dremio feature] [_driver _feature _db] supported?))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             metabase.driver impls                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod driver/display-name :dremio [_] "Dremio")
     
;;; +----------------------------------------------------------------------------------------------------------------+
;;; ----------------------------------------------------- Impls ------------------------------------------------------
;;; +----------------------------------------------------------------------------------------------------------------+

;; don't use the Postgres-specific metadata sync implementations. Dremio works better
;; with the generic SQL-JDBC metadata path.
(defmethod driver/describe-database* :dremio
  [& args]
  (apply (get-method driver/describe-database* :sql-jdbc) args))

(defmethod driver/describe-table :dremio
  [& args]
  (apply (get-method driver/describe-table :sql-jdbc) args))

(defmethod driver/describe-fields :dremio
  [& args]
  (apply (get-method driver/describe-fields :sql-jdbc) args))

;; Use a Dremio-compatible information_schema query for field sync instead of
;; inheriting Postgres catalog SQL via the driver hierarchy.
(defmethod sql-jdbc.sync/describe-fields-sql :dremio
  [driver & {:keys [schema-names table-names]}]
  (let [schema-condition (when (seq schema-names)
                           [:in [:lower :table_schema]
                            (mapv (fn [schema-name]
                                    [:inline (str/lower-case schema-name)])
                                  schema-names)])
        table-condition  (when (seq table-names)
                           [:in [:lower :table_name]
                            (mapv (fn [table-name]
                                    [:inline (str/lower-case table-name)])
                                  table-names)])
        where-clause     (cond-> [[:>= [:inline 1] [:inline 1]]]
                           schema-condition (conj schema-condition)
                           table-condition (conj table-condition))]
    (sql/format
     {:select [[:column_name :name]
               [[:- :ordinal_position [:inline 1]] :database-position]
               [:table_schema :table-schema]
               [:table_name :table-name]
               [[[:upper :data_type]] :database-type]
               [[:inline false] :database-is-auto-increment]
               [[:case-expr [:= :is_nullable [:inline "NO"]] [:inline true] [:inline false]]
                :database-required]
               [[:case-expr [:= :is_nullable [:inline "YES"]] [:inline true] [:inline false]]
                :database-is-nullable]
               [[:inline ""] :field-comment]]
      :from [[:information_schema.columns]]
      :where (vec (cons :and where-clause))
      :order-by [:table_schema :table_name :ordinal_position]}
     :dialect (sql.qp/quote-style driver))))

;; Postgres adds an enum-type preprocessing step during field sync that issues
;; pg_catalog queries. Dremio does not expose those catalogs, so keep the SQL
;; results unchanged.
(defmethod sql-jdbc.sync/describe-fields-pre-process-xf :dremio
  [_driver _database & _args]
  identity)

;; Dremio does not support the Postgres-style bulk FK SQL Metabase inherits via
;; :postgres, but it may still expose per-table metadata through JDBC. Try the
;; generic JDBC metadata path and degrade to no FKs rather than failing sync.
(defmethod driver/describe-fks :dremio
  [_driver _database & _args]
  [])

(defmethod driver/describe-table-fks :dremio
  [& args]
  (try
    (apply (get-method driver/describe-table-fks :sql-jdbc) args)
    (catch Exception e
      (log/warn e "Dremio JDBC foreign key metadata lookup failed; skipping FK sync for this table.")
      #{})))

                  
(defmethod sql-jdbc.conn/connection-details->spec :dremio
  [_ {:keys [user password schema host port ssl]
      :or {user "dbuser", password "dbpassword", schema "", host "localhost", port 31010}
      :as details}]
  (-> {:applicationName    config/mb-app-id-string
       :type :dremio
       :subprotocol "dremio"
       :subname (str "direct=" host ":" port (if-not (str/blank? schema) (str ";schema=" schema)))
       :user user
       :password password
       :host host
       :port port
       :classname "com.dremio.jdbc.Driver"
       :loginTimeout 10
       :ssl (boolean ssl)
       :sendTimeAsDatetime false}
      (sql-jdbc.common/handle-additional-options details, :seperator-style :semicolon)))

;; custom Dremio type handling
(def ^:private database-type->base-type
  (sql-jdbc.sync/pattern-based-database-type->base-type
    [[#"(?i)DOUBLE" :type/Float]       ; Dremio uses DOUBLE for float8
     [#"(?i)INTEGER" :type/Integer]    ; Dremio uses INTEGER for int4
     ]))

(defmethod sql-jdbc.sync/database-type->base-type :dremio
  [driver column-type]
  (or (database-type->base-type column-type)
      ((get-method sql-jdbc.sync/database-type->base-type :postgres) driver (keyword (str/lower-case (name column-type))))))

;; Dremio doesn't expose Postgres enum metadata. Avoid inheriting the Postgres
;; dynamic type lookup, which issues pg_catalog queries on every result-set
;; inspection.
(defmethod driver/dynamic-database-types-lookup :dremio
  [_driver _database _database-types]
  nil)


;; Dremio doesn't support "+ (INTERVAL '-30 day')"
(defmethod sql.qp/add-interval-honeysql-form :dremio
  [_ hsql-form amount unit]
  [:timestampadd [:raw (name unit)] amount (h2x/->timestamp hsql-form)])

(defn- date-trunc [unit expr] (sql/call :date_trunc (h2x/literal unit) (h2x/->timestamp expr)))

(defmethod sql.qp/date [:dremio :week]
  [_ _ expr]
  (sql.qp/adjust-start-of-week :dremio (partial date-trunc :week) expr))

;; bound variables are not supported in Dremio
(defmethod driver/execute-reducible-query :dremio
  [driver {:keys [database settings], {sql :query, :keys [params], :as inner-query} :native, :as outer-query} context respond]
  (let [inner-query (-> (assoc inner-query
                          :remark (qputil/query->remark :dremio outer-query)
                          :query  (if (seq params)
                                    (sql.qp/format-honeysql driver (cons sql params))
                                    sql)
                          :max-rows (mbql.u/query->max-rows-limit outer-query))
                        (dissoc :params))
        query       (assoc outer-query :native inner-query)]
    ((get-method driver/execute-reducible-query :postgres) driver query context respond)))

(defmethod sql.qp/format-honeysql :dremio
  [driver honeysql-form]
  (binding [driver/*compile-with-inline-parameters* true]
    ((get-method sql.qp/format-honeysql :postgres) driver honeysql-form)))

;; Dremio's cast DateTime to STRING methods (when unprepare)
(defmethod sql.qp/inline-value [:dremio OffsetDateTime]
  [_ t]
  (format "timestamp '%s'" (t/format "yyyy-MM-dd HH:mm:ss.SSS" t)))

(defmethod sql.qp/inline-value [:dremio ZonedDateTime]
  [driver t]
  (sql.qp/inline-value driver (t/offset-date-time t)))

;; Dremio is always in UTC
(defmethod driver/db-default-timezone :dremio [_ _]
           "UTC")

;; Dremio's jdbc doesn't support getObject(Class<T> type)
(prefer-method
  sql-jdbc.execute/read-column-thunk
  [::legacy/use-legacy-classes-for-read-and-set Types/TIMESTAMP]
  [:postgres Types/TIMESTAMP])

(prefer-method
  sql-jdbc.execute/read-column-thunk
  [::legacy/use-legacy-classes-for-read-and-set Types/TIME]
  [:postgres Types/TIME])
