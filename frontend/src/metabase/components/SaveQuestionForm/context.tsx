import {
  type PropsWithChildren,
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";

import { getCurrentUser } from "metabase/admin/datamodel/selectors";
import { useListRecentsQuery } from "metabase/api";
import { useGetDefaultCollectionId } from "metabase/collections/hooks";
import { isInstanceAnalyticsCollection } from "metabase/collections/utils";
import { FormProvider } from "metabase/forms";
import { useValidatedEntityId } from "metabase/lib/entity-id/hooks/use-validated-entity-id";
import { useSelector } from "metabase/lib/redux";
import { isNotNull } from "metabase/lib/types";
import type Question from "metabase-lib/v1/Question";
import type { CollectionId, DashboardId } from "metabase-types/api";

import { SAVE_QUESTION_SCHEMA } from "./schema";
import type { FormValues, SaveQuestionProps } from "./types";
import { getInitialValues, submitQuestion } from "./util";

type SaveQuestionContextType = {
  question: Question;
  originalQuestion: Question | null;
  initialValues: FormValues;
  handleSubmit: (details: FormValues) => Promise<void>;
  values: FormValues;
  setValues: (values: FormValues) => void;
  showSaveType: boolean;
  multiStep: boolean;
  targetCollection?: CollectionId;
  saveToDashboard?: DashboardId;
};

export const SaveQuestionContext =
  createContext<SaveQuestionContextType | null>(null);

/*
 * Why are we using these useState calls?
 *
 * When we use SaveQuestionModal within the QueryModals, the 'opened' prop on the modal
 * is always true. What this means is that the rendering of the modal is controlled by parent components,
 * and when the modal component opens, the modified question is passed into the provider. When the provider is rendered,
 * we calculate isSavedQuestionInitiallyChanged, the question and originalQuestion are different, so the form works as
 * it should.
 *
 * When we use the Modal's props to control the modal itself (i.e. no outside component controlling
 * the modal), the question and originalQuestion are the same when they are passed in to the provider
 * so isSavedQuestionInitiallyChanged will calculate to false and then *never* change because it's saved
 * as a state variable. This means that, to use this provider, we have to make sure that the question
 * and the original question are different *at the time of the Provider rendering*.
 *
 * Thanks for coming to my TED talk.
 * */
export const SaveQuestionProvider = ({
  question,
  originalQuestion: latestOriginalQuestion,
  onCreate,
  onSave,
  multiStep = false,
  targetCollection: userTargetCollection,
  children,
}: PropsWithChildren<SaveQuestionProps>) => {
  const [originalQuestion] = useState(latestOriginalQuestion); // originalQuestion from props changes during saving

  const defaultCollectionId = useGetDefaultCollectionId(
    originalQuestion?.collectionId(),
  );

  const currentUser = useSelector(getCurrentUser);
  const { id: collectionId } = useValidatedEntityId({
    type: "collection",
    id: userTargetCollection,
  });

  const targetCollection =
    collectionId ||
    (currentUser && userTargetCollection === "personal"
      ? currentUser.personal_collection_id
      : userTargetCollection);

  const [hasLoadedRecentItems, setHasLoadedRecentItems] = useState(false);
  const { data: recentItems, isLoading } = useListRecentsQuery(
    { context: ["selections"] },
    { skip: hasLoadedRecentItems },
  );
  // We need to stop refetching recent items as the user makes selections in the ui that could cause a refetch
  // This causes new initial values getting calculated, which combined with Formik's `enableReinitialize`
  // prop, results in a dirty form getting values replaced within initial state.
  useEffect(() => {
    if (!isLoading) {
      setHasLoadedRecentItems(true);
    }
  }, [isLoading]);

  const lastSelectedEntityModel = useMemo(() => {
    return recentItems?.find(
      (item) => item.model === "collection" || item.model === "dashboard",
    );
  }, [recentItems]);

  // we only care about the most recently select dashboard or collection
  const lastSelectedCollection =
    lastSelectedEntityModel?.model === "collection"
      ? lastSelectedEntityModel
      : undefined;

  const lastSelectedDashboard =
    lastSelectedEntityModel?.model === "dashboard"
      ? lastSelectedEntityModel
      : undefined;

  // analytics questions should not default to saving in dashboard
  const isAnalytics = isInstanceAnalyticsCollection(question.collection());

  const initialDashboardId =
    question.type() === "question" &&
    !isAnalytics &&
    lastSelectedDashboard?.can_write
      ? lastSelectedDashboard?.id
      : undefined;

  const initialCollectionId =
    (!isAnalytics
      ? lastSelectedDashboard?.parent_collection.id
      : defaultCollectionId) ??
    lastSelectedCollection?.id ??
    defaultCollectionId;

  const initialValues: FormValues = useMemo(
    () =>
      getInitialValues(
        originalQuestion,
        question,
        initialCollectionId,
        initialDashboardId,
      ),
    [originalQuestion, initialCollectionId, initialDashboardId, question],
  );

  const handleSubmit = useCallback(
    async (details: FormValues) =>
      submitQuestion({
        originalQuestion,
        details,
        question,
        onSave,
        onCreate,
        targetCollection,
      }),
    [originalQuestion, question, onSave, onCreate, targetCollection],
  );

  // we care only about the very first result as question can be changed before
  // the modal is closed
  const [isSavedQuestionInitiallyChanged] = useState(
    isNotNull(originalQuestion) && question.isDirtyComparedTo(originalQuestion),
  );

  const showSaveType =
    isSavedQuestionInitiallyChanged &&
    originalQuestion != null &&
    originalQuestion.type() !== "model" &&
    originalQuestion.type() !== "metric" &&
    originalQuestion.canWrite();

  const saveToDashboard = originalQuestion
    ? undefined
    : (question.dashboardId() ?? undefined);

  return (
    <FormProvider
      initialValues={initialValues}
      onSubmit={handleSubmit}
      validationSchema={SAVE_QUESTION_SCHEMA}
      enableReinitialize
    >
      {({ values, setValues }) => (
        <SaveQuestionContext.Provider
          value={{
            question,
            originalQuestion,
            initialValues,
            handleSubmit,
            values,
            setValues,
            showSaveType,
            multiStep,
            targetCollection,
            saveToDashboard,
          }}
        >
          {children}
        </SaveQuestionContext.Provider>
      )}
    </FormProvider>
  );
};

export const useSaveQuestionContext = () => {
  const context = useContext(SaveQuestionContext);
  if (!context) {
    throw new Error(
      "useSaveQuestionModalContext must be used within a SaveQuestionModalProvider",
    );
  }
  return context;
};
