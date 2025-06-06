(ns metabase.sync.field-values-test
  "Tests around the way Metabase syncs FieldValues, and sets the values of `field.has_field_values`."
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [java-time.api :as t]
   [metabase.analyze.core :as analyze]
   [metabase.sync.core :as sync]
   [metabase.sync.util-test :as sync.util-test]
   [metabase.test :as mt]
   [metabase.test.data :as data]
   [metabase.test.data.one-off-dbs :as one-off-dbs]
   [metabase.warehouse-schema.models.field-values :as field-values]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn- venues-price-field-values []
  (t2/select-one-fn :values :model/FieldValues, :field_id (mt/id :venues :price), :type :full))

(defn- sync-database!' [step database]
  (let [{:keys [step-info task-history]} (sync.util-test/sync-database! step database)]
    [(sync.util-test/only-step-keys step-info)
     (:task_details task-history)]))

(deftest sync-recreate-field-values-test
  (testing "Test that when we delete FieldValues syncing the Table again will cause them to be re-created"
    (testing "Check that we have expected field values to start with"
      ;; Manually activate Field values since they are not created during sync (#53387)
      (field-values/get-or-create-full-field-values! (t2/select-one :model/Field (mt/id :venues :price)))
      ;; Reset them to values that should get updated during sync
      (t2/update! :model/FieldValues :field_id (mt/id :venues :price) {:values [10 20 30 40]})

      ;; sync to make sure the field values are filled
      (sync-database!' "update-field-values" (data/db))
      (is (= [1 2 3 4]
             (venues-price-field-values))))
    (testing "Delete the Field values, make sure they're gone"
      (t2/delete! :model/FieldValues :field_id (mt/id :venues :price))
      (is (= nil
             (venues-price-field-values))))
    (testing "After the delete, a field values should not be created"
      (is (= (repeat 2 {:errors 0, :created 0, :updated 0, :deleted 0})
             (sync-database!' "update-field-values" (data/db)))))
    (testing "Now re-sync the table and make sure they're back"
      ;; Manually activate Field values since they are not created during sync (#53387)
      (field-values/get-or-create-full-field-values! (t2/select-one :model/Field (mt/id :venues :price)))
      ;; Reset them to values that should get updated during sync
      (t2/update! :model/FieldValues :field_id (mt/id :venues :price) {:values [10 20 30 40]})
      (sync/sync-table! (t2/select-one :model/Table :id (mt/id :venues)))
      (is (= [1 2 3 4]
             (venues-price-field-values))))))

(deftest sync-should-update-test
  (testing "Test that syncing will cause FieldValues to be updated"
    (testing "Check that we have expected field values to start with"
      ;; sync to make sure the field values are filled
      (sync-database!' "update-field-values" (data/db))
      (is (= [1 2 3 4]
             (venues-price-field-values))))
    (testing "Update the FieldValues, remove one of the values that should be there"
      (t2/update! :model/FieldValues (t2/select-one-pk :model/FieldValues :field_id (mt/id :venues :price) :type :full) {:values [1 2 3]})
      (is (= [1 2 3]
             (venues-price-field-values))))
    (testing "Now re-sync the table and validate the field values updated"
      (is (= (repeat 2 {:errors 0, :created 0, :updated 1, :deleted 0})
             (sync-database!' "update-field-values" (data/db)))))
    (testing "Make sure the value is back"
      (is (= [1 2 3 4]
             (venues-price-field-values))))))

(deftest sync-should-properly-handle-last-used-at
  (testing "Test that syncing will skip updating inactive FieldValues"
    (mt/with-full-data-perms-for-all-users!
      (t2/update! :model/FieldValues
                  (t2/select-one-pk :model/FieldValues :field_id (mt/id :venues :price) :type :full)
                  {:last_used_at (t/minus (t/offset-date-time) (t/days 20))
                   :values [1 2 3]})
      (is (= (repeat 2 {:errors 0, :created 0, :updated 0, :deleted 0})
             (sync-database!' "update-field-values" (data/db))))
      (is (= [1 2 3] (venues-price-field-values)))
      (testing "Fetching field values causes an on-demand update and marks Field Values as active"
        (is (partial= {:values [[1] [2] [3] [4]]}
                      (mt/user-http-request :rasta :get 200 (format "field/%d/values" (mt/id :venues :price)))))
        (is (t/after? (t2/select-one-fn :last_used_at :model/FieldValues :field_id (mt/id :venues :price) :type :full)
                      (t/minus (t/offset-date-time) (t/hours 2))))
        (testing "Field is syncing after usage"
          (t2/update! :model/FieldValues
                      (t2/select-one-pk :model/FieldValues :field_id (mt/id :venues :price) :type :full)
                      {:values [1 2 3]})
          (is (= (repeat 2 {:errors 0, :created 0, :updated 1, :deleted 0})
                 (sync-database!' "update-field-values" (data/db))))
          (is (partial= {:values [[1] [2] [3] [4]]}
                        (mt/user-http-request :rasta :get 200 (format "field/%d/values" (mt/id :venues :price))))))))))

(deftest sync-should-delete-expired-advanced-field-values-test
  (testing "Test that the expired Advanced FieldValues should be removed"
    (let [field-id                  (mt/id :venues :price)
          expired-created-at        (t/minus (t/offset-date-time) (t/plus field-values/advanced-field-values-max-age (t/days 1)))
          now                       (t/offset-date-time)
          [expired-sandbox-id
           expired-linked-filter-id
           valid-sandbox-id
           valid-linked-filter-id
           old-full-id
           new-full-id]             (t2/insert-returning-pks!
                                     (t2/table-name :model/FieldValues)
                                     [;; expired sandbox fieldvalues
                                      {:field_id   field-id
                                       :type       "advanced"
                                       :hash_key   "random-hash"
                                       :created_at expired-created-at
                                       :updated_at expired-created-at}
                                       ;; expired linked-filter fieldvalues
                                      {:field_id   field-id
                                       :type       "advanced"
                                       :hash_key   "random-hash"
                                       :created_at expired-created-at
                                       :updated_at expired-created-at}
                                       ;; valid sandbox fieldvalues
                                      {:field_id   field-id
                                       :type       "advanced"
                                       :hash_key   "random-hash"
                                       :created_at now
                                       :updated_at now}
                                       ;; valid linked-filter fieldvalues
                                      {:field_id   field-id
                                       :type       "advanced"
                                       :hash_key   "random-hash"
                                       :created_at now
                                       :updated_at now}
                                       ;; old full fieldvalues
                                      {:field_id   field-id
                                       :type       "full"
                                       :created_at expired-created-at
                                       :updated_at expired-created-at}
                                       ;; new full fieldvalues
                                      {:field_id   field-id
                                       :type       "full"
                                       :created_at now
                                       :updated_at now}])]
      (is (= (repeat 2 {:deleted 2})
             (sync-database!' "delete-expired-advanced-field-values" (data/db))))
      (testing "The expired Advanced FieldValues should be deleted"
        (is (not (t2/exists? :model/FieldValues :id [:in [expired-sandbox-id expired-linked-filter-id]]))))
      (testing "The valid Advanced FieldValues and full Fieldvalues(both old and new) should not be deleted"
        (is (t2/exists? :model/FieldValues :id [:in [valid-sandbox-id valid-linked-filter-id new-full-id old-full-id]]))))))

(deftest auto-list-with-cardinality-threshold-test
  ;; A Field with 50 values should get marked as `auto-list` on initial sync, because it should be 'list', but was
  ;; marked automatically, as opposed to explicitly (`list`)
  (one-off-dbs/with-blueberries-db
    ;; insert 50 rows & sync
    (one-off-dbs/insert-rows-and-sync! (one-off-dbs/range-str 50))
    ;; Manually activate Field values since they are not created during sync (#53387)
    (field-values/get-or-create-full-field-values! (t2/select-one :model/Field (mt/id :blueberries_consumed :str)))
    (testing "has_field_values should be auto-list"
      (is (= :auto-list
             (t2/select-one-fn :has_field_values :model/Field :id (mt/id :blueberries_consumed :str)))))

    (testing "... and it should also have some FieldValues"
      (is (= {:values                (one-off-dbs/range-str 50)
              :human_readable_values []
              :has_more_values       false}
             (into {} (t2/select-one [:model/FieldValues :values :human_readable_values :has_more_values]
                                     :field_id (mt/id :blueberries_consumed :str))))))

    ;; Manually add an advanced field values to test whether or not it got deleted later
    (t2/insert! :model/FieldValues {:field_id (mt/id :blueberries_consumed :str)
                                    :type :advanced
                                    :hash_key "random-key"})

    (testing "We mark the field values as :has_more_values when it grows too big."
      ;; now insert enough bloobs to put us over the limit and re-sync.
      (one-off-dbs/insert-rows-and-sync! (one-off-dbs/range-str 50 (+ 100 analyze/auto-list-cardinality-threshold)))
      (testing "has_field_values stay auto-list."
        (is (= :auto-list
               (t2/select-one-fn :has_field_values :model/Field :id (mt/id :blueberries_consumed :str)))))

      (testing "its FieldValues be limited."
        (is (=? {:values #(>= analyze/auto-list-cardinality-threshold (count %))
                 :has_more_values true}
                (t2/select-one :model/FieldValues
                               :field_id (mt/id :blueberries_consumed :str))))))))

(deftest auto-list-with-max-length-threshold-test
  (one-off-dbs/with-blueberries-db
    ;; insert 50 rows & sync
    (one-off-dbs/insert-rows-and-sync! [(str/join (repeat 50 "A"))])
    ;; Manually activate Field values since they are not created during sync (#53387)
    (field-values/get-or-create-full-field-values! (t2/select-one :model/Field (mt/id :blueberries_consumed :str)))
    (testing "has_field_values should be auto-list"
      (is (= :auto-list
             (t2/select-one-fn :has_field_values :model/Field :id (mt/id :blueberries_consumed :str)))))

    (testing "... and it should also have some FieldValues"
      (is (= {:values                [(str/join (repeat 50 "A"))]
              :human_readable_values []}
             (into {} (t2/select-one [:model/FieldValues :values :human_readable_values]
                                     :field_id (mt/id :blueberries_consumed :str))))))

    (testing "If the total length of all values exceeded the length threshold, it should get stay as auto list but be limitted"
      (one-off-dbs/insert-rows-and-sync! [(str/join (repeat 10 "B"))
                                          (str/join (repeat (+ 100 field-values/*total-max-length*) "X"))
                                          (str/join (repeat 10 "Z"))])
      (testing "has_field_values should have been set to nil."
        (is (= :auto-list
               (t2/select-one-fn :has_field_values :model/Field :id (mt/id :blueberries_consumed :str)))))

      (testing "Field values before the limit is reached are added"
        (is (=? {:has_more_values true
                 :values [(str/join (repeat 50 "A"))
                          (str/join (repeat 10 "B"))]}
                (t2/select-one :model/FieldValues
                               :field_id (mt/id :blueberries_consumed :str))))))))

(deftest list-with-cardinality-threshold-test
  (testing "If we had explicitly marked the Field as `list` (instead of `auto-list`)"
    (one-off-dbs/with-blueberries-db
      ;; insert 50 bloobs & sync
      (one-off-dbs/insert-rows-and-sync! (one-off-dbs/range-str 50))
      ;; change has_field_values to list
      ;; Manually activate Field values since they are not created during sync (#53387)
      (field-values/get-or-create-full-field-values! (t2/select-one :model/Field (mt/id :blueberries_consumed :str)))
      (t2/update! :model/Field (mt/id :blueberries_consumed :str) {:has_field_values "list"})
      (testing "has_more_values should initially be false"
        (is (= false
               (t2/select-one-fn :has_more_values :model/FieldValues :field_id (mt/id :blueberries_consumed :str)))))
      ;; Manually add an advanced field values to test whether or not it got deleted later
      (t2/insert! :model/FieldValues {:field_id (mt/id :blueberries_consumed :str)
                                      :type :advanced
                                      :hash_key "random-key"})
      (testing "adding more values even if it's exceed our cardinality limit, "
        (one-off-dbs/insert-rows-and-sync! (one-off-dbs/range-str 50 (+ 100 field-values/*absolute-max-distinct-values-limit*)))
        (testing "has_field_values shouldn't change and has_more_values should be true"
          (is (= :list
                 (t2/select-one-fn :has_field_values :model/Field
                                   :id (mt/id :blueberries_consumed :str)))))
        (testing "it should still have FieldValues, but the stored list has at most [metadata-queries/absolute-max-distinct-values-limit] elements"
          (is (= {:values                (take field-values/*absolute-max-distinct-values-limit*
                                               (one-off-dbs/range-str (+ 100 field-values/*absolute-max-distinct-values-limit*)))
                  :human_readable_values []
                  :has_more_values       true}
                 (into {} (t2/select-one [:model/FieldValues :values :human_readable_values :has_more_values]
                                         :field_id (mt/id :blueberries_consumed :str))))))
        (testing "The advanced field values of this field should be deleted"
          (is (= 0 (t2/count :model/FieldValues :field_id (mt/id :blueberries_consumed :str)
                             :type [:not= :full]))))))))

(deftest list-with-max-length-threshold-test
  (testing "If we had explicitly marked the Field as `list` (instead of `auto-list`) "
    (one-off-dbs/with-blueberries-db
      ;; insert a row with values contain 50 chars
      (one-off-dbs/insert-rows-and-sync! [(str/join (repeat 50 "A"))])
      ;; Manually activate Field values since they are not created during sync (#53387)
      (field-values/get-or-create-full-field-values! (t2/select-one :model/Field (mt/id :blueberries_consumed :str)))
      ;; change has_field_values to list
      (t2/update! :model/Field (mt/id :blueberries_consumed :str) {:has_field_values "list"})
      (testing "has_more_values should initially be false"
        (is (= false
               (t2/select-one-fn :has_more_values :model/FieldValues :field_id (mt/id :blueberries_consumed :str)))))

      (testing "insert a row with the value length exceeds our length limit\n"
        (one-off-dbs/insert-rows-and-sync! [(str/join (repeat (+ 100 field-values/*total-max-length*) "A"))])
        (testing "has_field_values shouldn't change and has_more_values should be true"
          (is (= :list
                 (t2/select-one-fn :has_field_values :model/Field
                                   :id (mt/id :blueberries_consumed :str)))))
        (testing "it should still have FieldValues, but the stored list is just a sub-list of all distinct values and `has_more_values` = true"
          (is (= {:values                [(str/join (repeat 50 "A"))]
                  :human_readable_values []
                  :has_more_values       true}
                 (into {} (t2/select-one [:model/FieldValues :values :human_readable_values :has_more_values]
                                         :field_id (mt/id :blueberries_consumed :str))))))))))

(deftest sync-aborts-on-non-recoverable-error-test
  (testing "Make sure sync aborts on non-recoverable errors"
    (one-off-dbs/with-blueberries-db
      ;; insert 50 rows & sync
      (one-off-dbs/insert-rows-and-sync! [(str/join (repeat 50 "A"))])
      ;; Manually activate Field values since they are not created during sync (#53387)
      (field-values/get-or-create-full-field-values! (t2/select-one :model/Field (mt/id :blueberries_consumed :str)))
      ;; we throw ConnectException, which is a non-recoverable exception
      (with-redefs [field-values/create-or-update-full-field-values! (fn [& _] (throw (java.net.ConnectException.)))]
        (is (=?
             {:steps [["delete-expired-advanced-field-values" {}]
                      ["update-field-values" {:throwable #(instance? Exception %)}]]}
             (sync/update-field-values! (data/db))))))))
