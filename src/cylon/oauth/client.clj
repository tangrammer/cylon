(ns cylon.oauth.client
  (:require
   [clojure.tools.logging :refer :all]
   [cylon.authentication :refer (authenticate)]
   [schema.core :as s]
   [java-net-http-client.client :refer (http-request)]
   [cylon.util :refer (as-set absolute-uri as-www-form-urlencoded as-query-string)]
      [clojure.data.json :as json]))

;; I don't think this is a wonderful name but until we can think of
;; something better :)
(defprotocol AccessTokenGrantee
  (solicit-access-token
    [_ req uri]
    [_ req uri scope-korks]
    "Initiate a process (typically via a HTTP redirect) that will result
    in a new request being made with an access token, if possible. Don't
    request specific scopes but get the defaults for the client.")

  (expired? [_ req access-token])

  (refresh-access-token [_ req]
    "Initiate a process (typically via a HTTP redirect) that will result
    in a new request being made with an access token, if possible."
    ))

;; Ring middleware to restrict a handler to a given role.
;; The algo in here should fit many usages. However, other functions
;; could be provided to implement different policies.

(defn wrap-require-authorization
  "Restrict a handler to a role. :identity and :access-token are added
  to the request. If a role is specified, also check that the role
  exists in the scope of the client. If role isn't specified, the
  identity and access-token are still retrieved."
  [h client & [scope]]
  (fn [req]
    (let [{access-token :cylon/access-token
           scopes :cylon/scopes
           sub :cylon/subject-identifier}
          (authenticate client req)]

      (cond
       (nil? access-token)
       (do
         (debugf "No access token, so soliciting one from client %s" client)
         (solicit-access-token client req (:authorize-uri client)))
       (expired? client req access-token)
       (do
         (debugf "access token has expired, seeking to refresh it")
         ;; The thinking here is that any refresh token that was returned
         ;; to the client will still be held by the client and can be
         ;; used to refresh the access-token
         (refresh-access-token client req))

       (and scope (not (contains? scopes scope)))
       ;; TODO Must do something better than this
       {:status 401 :body "Sorry, you just don't have enough privileges to access this page"}

       :otherwise
       (h (assoc req
            :cylon/subject-identifier sub
            :cylon/access-token access-token))))))


(defn http-request-form [method url params-map headers-map]
;  (println params-map)
  @(http-request
    (merge {:method method
      :url url
            :headers (merge {"content-type" "application/x-www-form-urlencoded"} (when headers-map
                                                                                   headers-map))
            }
           (when params-map
             { :body  (as-www-form-urlencoded params-map)})
           )

    ;; TODO Arguably we need better error handling here
    #(if (:error %)
       (do
         (errorf "Failed getting response from %s, response was %s" url %)
         %)
       (do
         (update-in % [:body] (fn [_] (json/read-str (:body %)))))))

  )

(defrecord HttpException [status body])


(defn refresh-token* [access-token-uri client-id client-secret refresh-token]
  (let [at-resp (http-request-form :post access-token-uri {"grant_type" "refresh_token"
                                                           "refresh_token" refresh-token
                                                           "client_id" client-id
                                                           "client_secret" client-secret} nil)]
    (if-let [error (:error at-resp)]
      (throw (Exception. (format "Something went wrong: status of underlying request, error was %s" error)
                         #_(map->HttpException {:status 403
                                   :body (format "Something went wrong: status of underlying request, error was %s" error)})))


      (if (not= (:status at-resp) 200)
        (throw (Exception. (format "Something went wrong: status of underlying request %s" (:status at-resp))
                           #_(map->HttpException {:status 403
                                     :body (format "Something went wrong: status of underlying request %s" (:status at-resp))})))


        (get (:body at-resp) "access_token"))))
  )
