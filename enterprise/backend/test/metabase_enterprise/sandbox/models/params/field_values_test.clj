(ns metabase-enterprise.sandbox.models.params.field-values-test
  (:require
   [clojure.test :refer :all]
   [java-time.api :as t]
   [metabase-enterprise.sandbox.models.params.field-values :as ee-params.field-values]
   [metabase-enterprise.test :as met]
   [metabase.parameters.field-values :as params.field-values]
   [metabase.request.core :as request]
   [metabase.test :as mt]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(deftest get-or-create-advanced-field-values!-test
  (testing "create a new field values and fix up the human readable values"
    (met/with-gtaps! {:gtaps {:categories {:query (mt/mbql-query categories {:filter [:and
                                                                                      [:> $id 3]
                                                                                      [:< $id 6]]})}}}
      ;; the categories-id doesn't have a field values, we fake it with a full fieldvalues to make it easier to test
      (t2/insert! :model/FieldValues {:type                  :full
                                      :field_id              (mt/id :categories :id)
                                      :values                (range 10)
                                      :human_readable_values (map #(str "id_" %) (range 10))})
      (let [categories-id (mt/id :categories :id)
            f             (t2/select-one :model/Field :id (mt/id :categories :id))
            card-id       (-> f :table_id (#'ee-params.field-values/table-id->gtap) :card :id)
            fv            (params.field-values/get-or-create-field-values! f)]
        (is (= [(range 4 6)]
               (t2/select-fn-vec :values :model/FieldValues
                                 :field_id categories-id :type :advanced
                                 {:order-by [:id]})))
        (is (= [4 5] (:values fv)))
        (is (= ["id_4" "id_5"] (:human_readable_values fv)))
        (is (some? (:hash_key fv)))

        (testing "call second time shouldn't create a new FieldValues"
          (params.field-values/get-or-create-field-values!
           (t2/select-one :model/Field :id (mt/id :categories :id)))
          (is (= 1 (t2/count :model/FieldValues :field_id categories-id :type :advanced))))

        (testing "after changing the question, should create new FieldValues"
          (let [new-query (mt/mbql-query categories
                            {:filter [:and [:> $id 1] [:< $id 4]]})]
            (Thread/sleep 1)
            (t2/update! :model/Card card-id {:dataset_query new-query
                                             :updated_at    (t/local-date-time)}))
          (params.field-values/get-or-create-field-values!
           (t2/select-one :model/Field :id (mt/id :categories :id)))
          (is (= [(range 4 6)
                  (range 2 4)]
                 (t2/select-fn-vec :values :model/FieldValues
                                   :field_id categories-id :type :advanced
                                   {:order-by [:id]}))))))))

(deftest advanced-field-values-hash-test
  (mt/with-premium-features #{:sandboxes}
    ;; copy at top level so that `with-gtaps-for-user!` does not have to create a new copy every time it gets called
    (mt/with-temp-copy-of-db
      (testing "gtap with remappings"
        (let [hash-input-for-user-id (fn [user-id login-attributes field]
                                       (met/with-gtaps-for-user! user-id
                                         {:gtaps      {:categories {:remappings {"State" [:dimension [:field (mt/id :categories :name) nil]]}}}
                                          :attributes login-attributes}
                                         (ee-params.field-values/hash-input-for-sandbox field)))
              field (t2/select-one :model/Field (mt/id :categories :name))]
          (mt/with-temp [:model/User {user-id-1 :id} {}
                         :model/User {user-id-2 :id} {}]

            (testing "2 users with the same attribute"
              (testing "should have the same hash for the same field"
                (is (= (hash-input-for-user-id user-id-1 {"State" "CA"} field)
                       (hash-input-for-user-id user-id-2 {"State" "CA"} field))))
              (testing "having extra login attributes won't effect the hash"
                (is (= (hash-input-for-user-id user-id-1 {"State" "CA"
                                                          "City"  "San Jose"} field)
                       (hash-input-for-user-id user-id-2 {"State" "CA"} field)))))

            (testing "2 users with the same attribute should have the different hash for different "
              (is (= (hash-input-for-user-id user-id-1 {"State" "CA"} field)
                     (hash-input-for-user-id user-id-2 {"State" "CA"} field))))

            (testing "same users but the login_attributes change should have different hash"
              (is (not= (hash-input-for-user-id user-id-1 {"State" "CA"} field)
                        (hash-input-for-user-id user-id-1 {"State" "NY"} field))))

            (testing "2 users with different login_attributes should have different hash"
              (is (not= (hash-input-for-user-id user-id-1 {"State" "CA"} field)
                        (hash-input-for-user-id user-id-2 {"State" "NY"} field)))
              (is (not= (hash-input-for-user-id user-id-1 {} field)
                        (hash-input-for-user-id user-id-2 {"State" "NY"} field))))))))))

(deftest advanced-field-values-hash-test-2
  (mt/with-premium-features #{:sandboxes}
    (testing "gtap with card and remappings"
      ;; hack so that we don't have to setup all the sandbox permissions the table
      (with-redefs [ee-params.field-values/field-is-sandboxed? (constantly true)]
        (let [field (t2/select-one :model/Field (mt/id :categories :name))
              hash-input-for-user-id-with-attributes (fn [user-id login-attributes field]
                                                       (mt/with-temp-vals-in-db :model/User user-id {:login_attributes login-attributes}
                                                         (request/with-current-user user-id
                                                           (ee-params.field-values/hash-input-for-sandbox field))))]
          (testing "2 users in the same group"
            (mt/with-temp
              [:model/Card                       {card-id :id} {}
               :model/PermissionsGroup           {group-id :id} {}
               :model/User                       {user-id-1 :id} {}
               :model/User                       {user-id-2 :id} {}
               :model/PermissionsGroupMembership _ {:group_id group-id
                                                    :user_id user-id-1}
               :model/PermissionsGroupMembership _ {:group_id group-id
                                                    :user_id user-id-2}
               :model/GroupTableAccessPolicy     _ {:card_id card-id
                                                    :group_id group-id
                                                    :table_id (mt/id :categories)
                                                    :attribute_remappings {"State" [:dimension [:field (mt/id :categories :name) nil]]}}]

              (testing "with same attributes, the hash should be the same field"
                (is (= (hash-input-for-user-id-with-attributes user-id-1 {"State" "CA"} field)
                       (hash-input-for-user-id-with-attributes user-id-2 {"State" "CA"} field))))

              (testing "with different attributes, the hash should be the different"
                (is (not= (hash-input-for-user-id-with-attributes user-id-1 {"State" "CA"} field)
                          (hash-input-for-user-id-with-attributes user-id-2 {"State" "NY"} field))))))

          (testing "gtap with native question"
            (mt/with-temp
              [:model/Card                       {card-id :id} {:query_type :native
                                                                :name "A native query"
                                                                :dataset_query
                                                                {:type :native
                                                                 :database (mt/id)
                                                                 :native
                                                                 {:query "SELECT * FROM People WHERE state = {{STATE}}"
                                                                  :template-tags
                                                                  {"STATE" {:id "72461b3b-3877-4538-a5a3-7a3041924517"
                                                                            :name "STATE"
                                                                            :display-name "STATE"
                                                                            :type "text"}}}}}
               :model/PermissionsGroup           {group-id :id} {}
               :model/User                       {user-id :id} {}
               :model/PermissionsGroupMembership _ {:group_id group-id
                                                    :user_id user-id}
               :model/GroupTableAccessPolicy     _ {:card_id card-id
                                                    :group_id group-id
                                                    :table_id (mt/id :categories)
                                                    :attribute_remappings {"State" [:variable [:template-tag "STATE"]]}}]
              (testing "same users but if the login_attributes change, they should have different hash (#24966)"
                (is (not= (hash-input-for-user-id-with-attributes user-id {"State" "CA"} field)
                          (hash-input-for-user-id-with-attributes user-id {"State" "NY"} field))))))

          (testing "2 users in different groups but gtaps use the same card"
            (mt/with-temp
              [:model/Card                       {card-id :id} {}

                 ;; user 1 in group 1
               :model/User                       {user-id-1 :id} {}
               :model/PermissionsGroup           {group-id-1 :id} {}
               :model/PermissionsGroupMembership _ {:group_id group-id-1
                                                    :user_id user-id-1}
               :model/GroupTableAccessPolicy     _ {:card_id card-id
                                                    :group_id group-id-1
                                                    :table_id (mt/id :categories)
                                                    :attribute_remappings {"State" [:dimension [:field (mt/id :categories :name) nil]]}}
                 ;; user 2 in group 2 with gtap using the same card
               :model/User                       {user-id-2 :id} {}
               :model/PermissionsGroup           {group-id-2 :id} {}
               :model/PermissionsGroupMembership _ {:group_id group-id-2
                                                    :user_id user-id-2}
               :model/GroupTableAccessPolicy     _ {:card_id card-id
                                                    :group_id group-id-2
                                                    :table_id (mt/id :categories)
                                                    :attribute_remappings {"State" [:dimension [:field (mt/id :categories :name) nil]]}}]
              (testing "with the same attributes, the hash should be the same"
                (is (= (hash-input-for-user-id-with-attributes user-id-1 {"State" "CA"} field)
                       (hash-input-for-user-id-with-attributes user-id-2 {"State" "CA"} field))))

              (testing "with different attributes, the hash should be the different"
                (is (not= (hash-input-for-user-id-with-attributes user-id-1 {"State" "CA"} field)
                          (hash-input-for-user-id-with-attributes user-id-2 {"State" "NY"} field))))))

          (testing "2 users in different groups and gtaps use 2 different cards"
            (mt/with-temp
              [:model/Card                       {card-id-1 :id} {}
               :model/User                       {user-id-1 :id} {}
               :model/PermissionsGroup           {group-id-1 :id} {}
               :model/PermissionsGroupMembership _ {:group_id group-id-1
                                                    :user_id user-id-1}
               :model/GroupTableAccessPolicy     _ {:card_id card-id-1
                                                    :group_id group-id-1
                                                    :table_id (mt/id :categories)
                                                    :attribute_remappings {"State" [:dimension [:field (mt/id :categories :name) nil]]}}
                 ;; user 2 in group 2 with gtap using card 2
               :model/Card                       {card-id-2 :id} {}
               :model/User                       {user-id-2 :id} {}
               :model/PermissionsGroup           {group-id-2 :id} {}
               :model/PermissionsGroupMembership _ {:group_id group-id-2
                                                    :user_id user-id-2}
               :model/GroupTableAccessPolicy     _ {:card_id card-id-2
                                                    :group_id group-id-2
                                                    :table_id (mt/id :categories)
                                                    :attribute_remappings {"State" [:dimension [:field (mt/id :categories :name) nil]]}}]
              (testing "the hash are different even though they have the same attribute"
                (is (not= (hash-input-for-user-id-with-attributes user-id-1 {"State" "CA"} field)
                          (hash-input-for-user-id-with-attributes user-id-2 {"State" "CA"} field)))
                (is (not= (hash-input-for-user-id-with-attributes user-id-1 {"State" "CA"} field)
                          (hash-input-for-user-id-with-attributes user-id-2 {"State" "NY"} field)))))))))))
