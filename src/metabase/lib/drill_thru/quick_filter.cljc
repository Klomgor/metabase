(ns metabase.lib.drill-thru.quick-filter
  "Adds a filter clause with simple operators like `<`, `>`, `=`, `≠`, `contains`, does-not-contain`.

  Entry points:

  - Cell

  Requirements:

  - Column not `type/PK`, `type/FK`, or `type/Structured`

  - Column can be filtered upon (exists in `filterableColumns`)

  - If the column is an aggregation, there must be breakouts. It doesn't make sense to filter on the value of a single
    row aggregation.

  - For `null` value, allow only `=` and `≠` operators, which map to `is-null` and `not-null` filter operators

  - For date and numeric columns, allow `<`, `>`, `=`, `≠` operators

  - For string columns which have `type/Comment` or `type/Description` semantic type, allow `contains` and
    `does-not-contain` operators.

  - For other cases, including string columns, allow only `=` and `≠` operators.

  - Return raw `value` in `displayInfo` for the drill. Is it used to show `Is ${value}` for string column operators.

  Query transformation:

  - Add a filter clause based on the selected column, value, and the operator

  - Append a query stage if the selected column is coming from an aggregation or breakout clause.

  Question transformation:

  - None

  There is a separate function `filterDrillDetails` which returns `query` and `column` used for the `FilterPicker`. It
  should automatically append a query stage and find the corresponding _filterable_ column in this stage. It is used
  for `contains` and `does-not-contain` operators."
  (:require
   [medley.core :as m]
   [metabase.lib.drill-thru.column-filter :as lib.drill-thru.column-filter]
   [metabase.lib.drill-thru.common :as lib.drill-thru.common]
   [metabase.lib.expression :as lib.expression]
   [metabase.lib.filter :as lib.filter]
   [metabase.lib.options :as lib.options]
   [metabase.lib.ref :as lib.ref]
   [metabase.lib.schema :as lib.schema]
   [metabase.lib.schema.common :as lib.schema.common]
   [metabase.lib.schema.drill-thru :as lib.schema.drill-thru]
   [metabase.lib.schema.expression :as lib.schema.expression]
   [metabase.lib.schema.metadata :as lib.schema.metadata]
   [metabase.lib.temporal-bucket :as lib.temporal-bucket]
   [metabase.lib.types.isa :as lib.types.isa]
   [metabase.lib.underlying :as lib.underlying]
   [metabase.util.malli :as mu]
   [metabase.util.number :as u.number]))

(defn- maybe-bigint->value-clause
  [value]
  (if-let [number (when (string? value) (u.number/parse-bigint value))]
    (lib.expression/value number)
    value))

(defn- operator [op & args]
  (lib.options/ensure-uuid (into [op {}] args)))

(mu/defn- operators-for :- [:sequential ::lib.schema.drill-thru/drill-thru.quick-filter.operator]
  [column :- ::lib.schema.metadata/column
   value]
  (let [field-ref (cond-> (lib.ref/ref column)
                    (:temporal-unit column)
                    (lib.temporal-bucket/with-temporal-bucket (:temporal-unit column)))]
    (cond
      (lib.types.isa/structured? column)
      []

      (= value :null)
      (for [[op label] (if (or (lib.types.isa/string? column) (lib.types.isa/string-like? column))
                         [[:is-empty "="] [:not-empty "≠"]]
                         [[:is-null "="] [:not-null "≠"]])]
        {:name   label
         :filter (operator op field-ref)})

      (or (lib.types.isa/numeric? column)
          (lib.types.isa/temporal? column))
      (for [[op label] [[:<  "<"]
                        [:>  ">"]
                        [:=  "="]
                        [:!= "≠"]]
            :when (or (not (#{:< :>} op))
                      (lib.schema.expression/comparable-expressions? field-ref value))]
        {:name   label
         :filter (operator op field-ref (cond-> value
                                          (lib.types.isa/numeric? column) maybe-bigint->value-clause))})

      (and (lib.types.isa/string? column)
           (or (lib.types.isa/comment? column)
               (lib.types.isa/description? column)))
      (for [[op label] [[:contains "contains"]
                        [:does-not-contain "does-not-contain"]]]
        {:name   label
         :filter (operator op field-ref value)})

      :else
      (for [[op label] [[:=  "="]
                        [:!= "≠"]]]
        {:name   label
         :filter (operator op field-ref value)}))))

(mu/defn quick-filter-drill :- [:maybe ::lib.schema.drill-thru/drill-thru.quick-filter]
  "Filter the current query based on the value clicked.

  The options vary depending on the type of the field:
  - `:is-null` and `:not-null` for a `NULL` value;
  - `:=` and `:!=` for everything else;
  - plus `:<` and `:>` for numeric and date columns.

  Note that this returns a single `::drill-thru` value with 1 or more `:operators`; these are rendered as a set of small
  buttons in a single row of the drop-down."
  [query                                                      :- ::lib.schema/query
   stage-number                                               :- :int
   {:keys [column column-ref dimensions value], :as _context} :- ::lib.schema.drill-thru/context]
  (when (and (lib.drill-thru.common/mbql-stage? query stage-number)
             column
             (some? value) ; Deliberately allows value :null, only a missing value should fail this test.
             ;; If this is an aggregation, there must be breakouts (dimensions).
             (or (not (lib.underlying/aggregation-sourced? query column))
                 (seq dimensions))
             (not (lib.types.isa/structured?  column))
             (not (lib.drill-thru.common/primary-key? query stage-number column))
             (not (lib.drill-thru.common/foreign-key? query stage-number column)))
    ;; For aggregate columns, we want to introduce a new stage when applying the drill-thru.
    ;; [[lib.drill-thru.column-filter/prepare-query-for-drill-addition]] handles this. (#34346)
    (when-let [drill-details (lib.drill-thru.column-filter/prepare-query-for-drill-addition
                              query stage-number column column-ref :filter)]
      (let [temporal-unit (lib.temporal-bucket/temporal-bucket column-ref)
            column (cond-> (:column drill-details)
                     temporal-unit (assoc :temporal-unit temporal-unit))]
        (merge drill-details
               {:lib/type   :metabase.lib.drill-thru/drill-thru
                :type       :drill-thru/quick-filter
                :operators  (operators-for column value)
                :value      value})))))

(defmethod lib.drill-thru.common/drill-thru-info-method :drill-thru/quick-filter
  [_query _stage-number drill-thru]
  (-> (select-keys drill-thru [:type :operators :value])
      (update :value lib.drill-thru.common/drill-value->js)
      (update :operators (fn [operators]
                           (mapv :name operators)))))

(mu/defmethod lib.drill-thru.common/drill-thru-method :drill-thru/quick-filter :- ::lib.schema/query
  [_query                      :- ::lib.schema/query
   _stage-number               :- :int
   {:keys [query stage-number]
    :as drill}                 :- ::lib.schema.drill-thru/drill-thru.quick-filter
   filter-op                   :- ::lib.schema.common/non-blank-string]
  (let [quick-filter (or (m/find-first #(= (:name %) filter-op) (:operators drill))
                         (throw (ex-info (str "No matching filter for operator " filter-op)
                                         {:drill-thru   drill
                                          :operator     filter-op
                                          :query        query
                                          :stage-number stage-number})))]
    (lib.filter/filter query stage-number (:filter quick-filter))))
