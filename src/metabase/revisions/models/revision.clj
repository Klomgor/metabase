(ns metabase.revisions.models.revision
  (:require
   [clojure.data :as data]
   [metabase.config.core :as config]
   [metabase.models.interface :as mi]
   [metabase.revisions.models.revision.diff :refer [diff-strings*]]
   [metabase.util :as u]
   [metabase.util.i18n :refer [deferred-tru tru]]
   [metabase.util.json :as json]
   [metabase.util.malli :as mu]
   [methodical.core :as methodical]
   [toucan2.core :as t2]
   [toucan2.model :as t2.model]))

(defn toucan-model?
  "Check if `model` is a toucan model."
  [model]
  (isa? model :metabase/model))

(def ^:const max-revisions
  "Maximum number of revisions to keep for each individual object. After this limit is surpassed, the oldest revisions
  will be deleted."
  15)

(defmulti serialize-instance
  "Prepare an instance for serialization in a Revision."
  {:arglists '([model id instance])}
  mi/dispatch-on-model)

;;; no default implementation for [[serialize-instance]]; models need to implement this themselves.

(defmulti revert-to-revision!
  "Return an object to the state recorded by `serialized-instance`."
  {:arglists '([model id user-id serialized-instance])}
  mi/dispatch-on-model)

(defmethod revert-to-revision! :default
  [model id _user-id serialized-instance]
  (let [valid-columns   (keys (t2/select-one (t2/table-name model) :id id))
        ;; Only include fields that we know are on the model in the current version of Metabase! Otherwise we'll get
        ;; an error if a field in an earlier version has since been dropped, but is still present in the revision.
        ;; This is best effort — other kinds of schema changes could still break the ability to revert successfully.
        revert-instance (select-keys serialized-instance valid-columns)]
    (t2/update! model id revert-instance)))

(defmulti diff-map
  "Return a map describing the difference between `object-1` and `object-2`."
  {:arglists '([model object-1 object-2])}
  mi/dispatch-on-model)

(defmethod diff-map :default
  [_model o1 o2]
  (when o1
    (let [[before after] (data/diff o1 o2)]
      {:before before
       :after  after})))

(defmulti diff-strings
  "Return a seq of string describing the difference between `object-1` and `object-2`.

  Each string in the seq should be i18n-ed."
  {:arglists '([model object-1 object-2])}
  mi/dispatch-on-model)

(defmethod diff-strings :default
  [model o1 o2]
  (diff-strings* (name model) o1 o2))

;;; ----------------------------------------------- Entity & Lifecycle -----------------------------------------------

(methodical/defmethod t2/table-name :model/Revision [_model] :revision)

(doto :model/Revision
  (derive :metabase/model)
  (derive :hook/search-index))

(t2/deftransforms :model/Revision
  {:object mi/transform-json})

(t2/define-before-insert :model/Revision
  [{:keys [model model_id] :as revision}]
  ;; obtain a lock on the existing revisions for this entity to prevent concurrent inserts of new revisions
  (t2/query {:select [:id]
             :from [:revision]
             :where [:and
                     [:= :model model]
                     [:= :model_id model_id]]
             :for :update})
  (assoc revision
         :timestamp (or (:timestamp revision) :%now)
         :metabase_version config/mb-version-string
         :most_recent true))

(t2/define-before-update :model/Revision
  [_revision]
  (fn [& _] (throw (Exception. (tru "You cannot update a Revision!")))))

(t2/define-after-select :model/Revision
  ;; Call the appropriate `post-select` methods (including the type functions) on the `:object` this Revision recorded.
  ;; This is important for things like Card revisions, where the `:dataset_query` property needs to be normalized when
  ;; coming out of the DB.
  [{:keys [model] :as revision}]
  ;; in some cases (such as tests) we have 'fake' models that cannot be resolved normally; don't fail entirely in
  ;; those cases
  (let [model (u/ignore-exceptions (t2.model/resolve-model (symbol model)))]
    (cond-> revision
      model (update :object (partial mi/do-after-select model)))))

(defn- delete-old-revisions!
  "Delete old revisions of `model` with `id` when there are more than `max-revisions` in the DB."
  [model id]
  (when-let [old-revisions (seq (drop max-revisions (t2/select-fn-vec :id :model/Revision
                                                                      :model    (name model)
                                                                      :model_id id
                                                                      {:order-by [[:timestamp :desc]
                                                                                  [:id :desc]]})))]
    (t2/delete! :model/Revision :id [:in old-revisions])))

(t2/define-after-insert :model/Revision
  [revision]
  (u/prog1 revision
    (let [{:keys [id model model_id]} revision]
      ;; Note 1: Update the last `most_recent revision` to false (not including the current revision)
      ;; Note 2: We don't allow updating revision but this is a special case, so we by pass the check by
      ;; updating directly with the table name
      (t2/update! (t2/table-name :model/Revision)
                  {:model model :model_id model_id :most_recent true :id [:not= id]}
                  {:most_recent false})
      (delete-old-revisions! model model_id))))

;;; # Functions

(defn- revision-changes
  [model prev-revision revision]
  (cond
    (:is_creation revision)  [(deferred-tru "created this")]
    (:is_reversion revision) [(deferred-tru "reverted to an earlier version")]
    ;; We only keep [[revision/max-revisions]] number of revision per entity.
    ;; prev-revision can be nil when we generate description for oldest revision
    (nil? prev-revision)     [(deferred-tru "modified this")]
    :else                    (diff-strings model (:object prev-revision) (:object revision))))

(defn- revision-description-info
  [model prev-revision revision]
  (let [changes (revision-changes model prev-revision revision)]
    {:description          (if (seq changes)
                             (u/build-sentence changes)
                             ;; HACK: before #30285 we record revision even when there is nothing changed,
                             ;; so there are cases when revision can comeback as `nil`.
                             ;; This is a safe guard for us to not display "Crowberto null" as
                             ;; description on UI
                             (deferred-tru "created a revision with no change."))
     ;; this is used on FE
     :has_multiple_changes (> (count changes) 1)}))

(defn add-revision-details
  "Add enriched revision data such as `:diff` and `:description` as well as filter out some unnecessary props."
  [model revision prev-revision]
  (-> revision
      (assoc :diff (diff-map model (:object prev-revision) (:object revision)))
      (merge (revision-description-info model prev-revision revision))
      ;; add revision user details
      (t2/hydrate :user)
      (update :user select-keys [:id :first_name :last_name :common_name])
      ;; Filter out irrelevant info
      (dissoc :model :model_id :user_id :object)))

(mu/defn revisions
  "Get the revisions for `model` with `id` in reverse chronological order."
  [model :- [:fn toucan-model?]
   id    :- pos-int?]
  (let [model-name (name model)]
    (t2/select :model/Revision :model model-name :model_id id {:order-by [[:id :desc]]})))

(mu/defn revisions+details
  "Fetch `revisions` for `model` with `id` and add details."
  [model :- [:fn toucan-model?]
   id    :- pos-int?]
  (when-let [revisions (revisions model id)]
    (loop [acc [], [r1 r2 & more] revisions]
      (if-not r2
        (conj acc (add-revision-details model r1 nil))
        (recur (conj acc (add-revision-details model r1 r2))
               (conj more r2))))))

(mu/defn push-revision!
  "Record a new Revision for `entity` with `id` if it's changed compared to the last revision.
  Returns `object` or `nil` if the object does not changed."
  [{:keys [id entity user-id object
           is-creation? message]
    :or   {is-creation? false}}     :- [:map {:closed true}
                                        [:id                            pos-int?]
                                        [:object                        :map]
                                        [:entity                        [:fn toucan-model?]]
                                        [:user-id                       pos-int?]
                                        [:is-creation? {:optional true} [:maybe :boolean]]
                                        [:message      {:optional true} [:maybe :string]]]]
  (let [entity-name (name entity)
        serialized-object (serialize-instance entity id (dissoc object :message))
        last-object       (t2/select-one-fn :object :model/Revision :model entity-name :model_id id {:order-by [[:id :desc]]})]
    ;; make sure we still have a map after calling out serialization function
    (assert (map? serialized-object))
    ;; the last-object could have nested object, e.g: Dashboard can have multiple Card in it,
    ;; even though we call `post-select` on the `object`, the nested object might not be transformed correctly
    ;; E.g: Cards inside Dashboard will not be transformed
    ;; so to be safe, we'll just compare them as string
    (when-not (= (json/encode serialized-object)
                 (json/encode last-object))
      (t2/insert! :model/Revision
                  :model        entity-name
                  :model_id     id
                  :user_id      user-id
                  :object       serialized-object
                  :is_creation  is-creation?
                  :is_reversion false
                  :message      message)
      object)))

(mu/defn revert!
  "Revert `entity` with `id` to a given Revision."
  [info :- [:map {:closed true}
            [:id          pos-int?]
            [:user-id     pos-int?]
            [:revision-id pos-int?]
            [:entity      [:fn toucan-model?]]]]
  (let [{:keys [id user-id revision-id entity]} info
        model-name (name entity)
        serialized-instance (t2/select-one-fn :object :model/Revision :model model-name :model_id id :id revision-id)]
    (t2/with-transaction [_conn]
      ;; Do the reversion of the object
      (revert-to-revision! entity id user-id serialized-instance)
      ;; Push a new revision to record this change
      (let [last-revision (t2/select-one :model/Revision :model model-name, :model_id id, {:order-by [[:id :desc]]})
            new-revision  (first (t2/insert-returning-instances! :model/Revision
                                                                 :model        model-name
                                                                 :model_id     id
                                                                 :user_id      user-id
                                                                 :object       serialized-instance
                                                                 :is_creation  false
                                                                 :is_reversion true))]
        (add-revision-details entity new-revision last-revision)))))
