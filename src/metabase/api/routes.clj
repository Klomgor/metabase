(ns metabase.api.routes
  (:require [compojure.core :refer [context defroutes GET]]
            [compojure.route :as route]
            (metabase.api [activity :as activity]
                          [card :as card]
                          [dash :as dash]
                          [database :as database]
                          [dataset :as dataset]
                          [field :as field]
                          [notify :as notify]
                          [revision :as revision]
                          [session :as session]
                          [setting :as setting]
                          [setup :as setup]
                          [table :as table]
                          [tiles :as tiles]
                          [user :as user])
            (metabase.api.meta [fk :as fk])
            [metabase.middleware.auth :as auth]))

(def ^:private +apikey
  "Wrap API-ROUTES so they may only be accessed with proper apikey credentials."
  auth/enforce-api-key)

(def ^:private +auth
  "Wrap API-ROUTES so they may only be accessed with proper authentiaction credentials."
  auth/enforce-authentication)

(defroutes routes
  (context "/activity"     [] (+auth activity/routes))
  (context "/card"         [] (+auth card/routes))
  (context "/dash"         [] (+auth dash/routes))
  (context "/database"     [] (+auth database/routes))
  (context "/dataset"      [] (+auth dataset/routes))
  (context "/field"        [] (+auth field/routes))
  (GET     "/health"       [] {:status 200 :body {:status "ok"}})
  (context "/meta/fk"      [] (+auth fk/routes))
  (context "/notify"       [] (+apikey notify/routes))
  (context "/revision"     [] (+auth revision/routes))
  (context "/session"      [] session/routes)
  (context "/setting"      [] (+auth setting/routes))
  (context "/setup"        [] setup/routes)
  (context "/table"        [] (+auth table/routes))
  (context "/tiles"        [] (+auth tiles/routes))
  (context "/user"         [] (+auth user/routes))
  (route/not-found (fn [{:keys [request-method uri]}]
                     {:status 404
                      :body   (str (.toUpperCase (name request-method)) " " uri " is not yet implemented.")})))
