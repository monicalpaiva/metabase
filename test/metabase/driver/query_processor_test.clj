(ns metabase.driver.query-processor-test
  "Query processing tests that can be ran between any of the available drivers, and should give the same results."
  (:require [expectations :refer :all]
            [metabase.db :refer :all]
            [metabase.driver :as driver]
            [metabase.driver.mongo.test-data :as mongo-data]
            (metabase.models [table :refer [Table]])
            [metabase.test-data :as generic-sql-data]))

;; ## Functionality for writing driver-independent tests

(def ^:dynamic *db* "Bound to `Database` for the current driver inside body of `with-driver`.")
(def ^:dynamic *db-id* "Bound to ID of `Database` for the current driver inside body of `with-driver`.")
(def ^:dynamic *driver-data*)

(defprotocol DriverData
  (db [this]
    "Return `Database` containing test data for this driver.")
  (table-name->table [this table-name]
    "Given a TABLE-NAME keyword like `:venues`, *fetch* the corresponding `Table`.")
  (field-name->id [this table-name field-name])
  (fks-supported? [this])
  (format-name [this table-or-field-name])
  (id-field-type [this]))

(deftype MongoDriverData []
  DriverData
  (db [_]
    @mongo-data/mongo-test-db)
  (table-name->table [_ table-name]
    (mongo-data/table-name->table table-name))
  (field-name->id [_ table-name field-name]
    (mongo-data/field-name->id table-name (if (= field-name :id) :_id
                                              field-name)))
  (fks-supported? [_]
    false)
  (format-name [_ table-or-field-name]
    (if (= table-or-field-name "id") "_id"
        table-or-field-name))

  (id-field-type [_]
    :IntegerField))

(deftype GenericSqlDriverData []
  DriverData
  (db [_]
    @generic-sql-data/test-db)
  (table-name->table [_ table-name]
    (generic-sql-data/table-name->table table-name))
  (field-name->id [_ table-name field-name]
    (generic-sql-data/field->id table-name field-name))
  (fks-supported? [_]
    true)
  (format-name [_ table-or-field-name]
    (clojure.string/upper-case table-or-field-name))
  (id-field-type [_]
    :BigIntegerField))

(def driver-name->driver-data
  {:mongo       (MongoDriverData.)
   :generic-sql (GenericSqlDriverData.)})

(def driver-name->db-delay
  "Map of `driver-name` to a delay that will return the corresponding `Database`."
  {:mongo       mongo-data/mongo-test-db
   :generic-sql generic-sql-data/test-db})
(def valid-driver-names (set (keys driver-name->db-delay)))

(defmacro with-driver
  "Execute BODY with `*db*` and `*db-id*` bound to appropriate places for "
  [driver-name & body]
  {:pre [(keyword? driver-name)
         (contains? valid-driver-names driver-name)]}
  `(let [driver-data# (driver-name->driver-data ~driver-name)
         db#          (db driver-data#)]
     (binding [*driver-data* driver-data#
               *db* db#
               *db-id* (:id db#)]
       (assert (and (integer? *db-id*)
                    (map? *db*)))
       ~@body)))

(defmacro expect-with-all-drivers
  "Like expect, but runs a test inside of `with-driver` for *each* of the available drivers."
  [expected actual]
  `(do ~@(mapcat (fn [driver-name]
                   `[(expect
                         (with-driver ~driver-name
                           ~expected)
                       (with-driver ~driver-name
                         ~actual))])
                 valid-driver-names)))


;; ##  Driver-Independent Data Fns

(defn ->table
  "Given keyword TABLE-NAME, fetch corresponding `Table` in `*db*`."
  [table-name]
  {:pre [*driver-data*]
   :post [(map? %)]}
  (table-name->table *driver-data* table-name))

(defn ->table-id [table-name]
  {:pre [*driver-data*]
   :post [(integer? %)]}
  (:id (->table table-name)))

;; (def ^{:arglists '([table-name])} table-name->id
;;   "Given keyword TABLE-NAME, fetch ID of corresponding `Table` in `*db*`."
;;   (let [-table-name->id (memoize (fn [db-id table-name]
;;                                    {:pre [(integer? db-id)
;;                                           (keyword? table-name)]
;;                                     :post [(integer? %)]}
;;                                    (sel :one :id Table :db_id db-id :name (name table-name))))]
;;     (fn [table-name]
;;       {:pre [(integer? *db-id*)]}
;;       (-table-name->id *db-id* table-name))))

(defn ->field-id [table-name field-name]
  {:pre [*driver-data*]
   :post [(integer? %)]}
  (field-name->id *driver-data* table-name field-name))


;; ## Driver-Independent QP Tests

(defmacro qp-expect-with-all-drivers
  "Slightly more succinct way of writing QP tests. Adds standard boilerplate to actual/expected forms."
  [data query]
  `(expect-with-all-drivers
       {:status :completed
        :row_count ~(count (:rows data))
        :data ~data}
     (driver/process-query {:type :query
                            :database *db-id*
                            :query ~query})))

(defn venues-columns []
  [(format-name *driver-data* "id")
   (format-name *driver-data* "category_id")
   (format-name *driver-data* "price")
   (format-name *driver-data* "longitude")
   (format-name *driver-data* "latitude")
   (format-name *driver-data* "name")])

(defn venues-cols []
  [{:extra_info {}
    :special_type :id
    :base_type (id-field-type *driver-data*)
    :description nil
    :name (format-name *driver-data* "id")
    :table_id (->table-id :venues)
    :id (->field-id :venues :id)}
   {:extra_info (if (fks-supported? *driver-data*) {:target_table_id (->table-id :categories)}
                    {})
    :special_type (if (fks-supported? *driver-data*) :fk
                      :category)
    :base_type :IntegerField
    :description nil
    :name (format-name *driver-data* "category_id")
    :table_id (->table-id :venues)
    :id (->field-id :venues :category_id)}
   {:extra_info {}
    :special_type :category
    :base_type :IntegerField
    :description nil
    :name (format-name *driver-data* "price")
    :table_id (->table-id :venues)
    :id (->field-id :venues :price)}
   {:extra_info {}
    :special_type :longitude,
    :base_type :FloatField,
    :description nil
    :name (format-name *driver-data* "longitude")
    :table_id (->table-id :venues)
    :id (->field-id :venues :longitude)}
   {:extra_info {}
    :special_type :latitude
    :base_type :FloatField
    :description nil
    :name (format-name *driver-data* "latitude")
    :table_id (->table-id :venues)
    :id (->field-id :venues :latitude)}
   {:extra_info {}
    :special_type nil
    :base_type :TextField
    :description nil
    :name (format-name *driver-data* "name")
    :table_id (->table-id :venues)
    :id (->field-id :venues :name)}])

;; ### "COUNT" AGGREGATION
(qp-expect-with-all-drivers
    {:rows [[100]]
     :columns ["count"]
     :cols [{:base_type :IntegerField
             :special_type :number
             :name "count"
             :id nil
             :table_id nil
             :description nil}]}
  {:source_table (->table-id :venues)
   :filter [nil nil]
   :aggregation ["count"]
   :breakout [nil]
   :limit nil})

;; ### "SUM" AGGREGATION
(qp-expect-with-all-drivers
    {:rows [[203]]
     :columns ["sum"]
     :cols [{:base_type :IntegerField
             :special_type :category
             :name "sum"
             :id nil
             :table_id nil
             :description nil}]}
  {:source_table (->table-id :venues)
   :filter [nil nil]
   :aggregation ["sum" (->field-id :venues :price)]
   :breakout [nil]
   :limit nil})

;; ### "DISTINCT COUNT" AGGREGATION
(qp-expect-with-all-drivers
    {:rows [[15]]
     :columns ["count"]
     :cols [{:base_type :IntegerField
             :special_type :number
             :name "count"
             :id nil
             :table_id nil
             :description nil}]}
  {:source_table (->table-id :checkins)
   :filter [nil nil]
   :aggregation ["distinct" (->field-id :checkins :user_id)]
   :breakout [nil]
   :limit nil})


;; ## "ROWS" AGGREGATION
;; Test that a rows aggregation just returns rows as-is.
(qp-expect-with-all-drivers
    {:rows [[1 4 3 -165.374 10.0646 "Red Medicine"]
            [2 11 2 -118.329 34.0996 "Stout Burgers & Beers"]
            [3 11 2 -118.428 34.0406 "The Apple Pan"]
            [4 29 2 -118.465 33.9997 "Wurstküche"]
            [5 20 2 -118.261 34.0778 "Brite Spot Family Restaurant"]
            [6 20 2 -118.324 34.1054 "The 101 Coffee Shop"]
            [7 44 2 -118.305 34.0689 "Don Day Korean Restaurant"]
            [8 11 2 -118.342 34.1015 "25°"]
            [9 71 1 -118.301 34.1018 "Krua Siri"]
            [10 20 2 -118.292 34.1046 "Fred 62"]]
     :columns (venues-columns)
     :cols (venues-cols)}
  {:source_table (->table-id :venues)
   :filter nil
   :aggregation ["rows"]
   :breakout [nil]
   :limit 10
   :order_by [[(->field-id :venues :id) "ascending"]]})


;; ## "PAGE" CLAUSE
;; Test that we can get "pages" of results.

;; ### PAGE - Get the first page
(qp-expect-with-all-drivers
    {:rows [[1 "African"]
            [2 "American"]
            [3 "Artisan"]
            [4 "Asian"]
            [5 "BBQ"]]
     :columns [(format-name *driver-data* "id"), (format-name *driver-data* "name")]
     :cols [{:extra_info {} :special_type :id, :base_type (id-field-type *driver-data*), :description nil, :name (format-name *driver-data* "id")
             :table_id (->table-id :categories), :id (->field-id :categories :id)}
            {:extra_info {} :special_type nil, :base_type :TextField, :description nil, :name (format-name *driver-data* "name")
             :table_id (->table-id :categories), :id (->field-id :categories :name)}]}
  {:source_table (->table-id :categories)
   :aggregation ["rows"]
   :page {:items 5
          :page 1}
   :order_by [[(->field-id :categories :name) "ascending"]]})

;; ### PAGE - Get the second page
(qp-expect-with-all-drivers
    {:rows [[6 "Bakery"]
            [7 "Bar"]
            [8 "Beer Garden"]
            [9 "Breakfast / Brunch"]
            [10 "Brewery"]]
     :columns [(format-name *driver-data* "id"), (format-name *driver-data* "name")]
     :cols [{:extra_info {} :special_type :id, :base_type (id-field-type *driver-data*), :description nil, :name (format-name *driver-data* "id")
             :table_id (->table-id :categories), :id (->field-id :categories :id)}
            {:extra_info {} :special_type nil, :base_type :TextField, :description nil, :name (format-name *driver-data* "name")
             :table_id (->table-id :categories), :id (->field-id :categories :name)}]}
  {:source_table (->table-id :categories)
   :aggregation ["rows"]
   :page {:items 5
          :page 2}
   :order_by [[(->field-id :categories :name) "ascending"]]})
