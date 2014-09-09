(ns cylon.oauth.impl.authorization-server
  (require
   [com.stuartsierra.component :as component]
   [clojure.tools.logging :refer :all]
   [modular.bidi :refer (WebService)]
   [bidi.bidi :refer (path-for)]
   [clojure.set :as set]
   [hiccup.core :refer (html h)]
   [schema.core :as s]
   [plumbing.core :refer (<-)]
   [clojure.string :as str]
   [cylon.oauth.client-registry :refer (lookup-client+)]
   [cylon.oauth.authorization :refer (AccessTokenAuthorizer authorized?)]
   [cylon.authorization :refer (RequestAuthorizer request-authorized?)]
   [cylon.authentication :refer (initiate-authentication-interaction get-result clean-resources!)]
   [cylon.user :refer (verify-user)]
   [cylon.totp :refer (OneTimePasswordStore get-totp-secret totp-token)]
   [clj-time.core :refer (now plus days)]
   [cheshire.core :refer (encode)]
   [clj-jwt.core :refer (to-str sign jwt)]
   [ring.middleware.params :refer (wrap-params)]
   [ring.middleware.cookies :refer (cookies-request)]
   [cylon.session :refer (create-session! assoc-session! ->cookie get-session-value get-session-id get-session cookies-response-with-session get-session-from-cookie)]
   [ring.middleware.cookies :refer (wrap-cookies cookies-request cookies-response)]
   [ring.util.response :refer (redirect)]
   [cylon.oauth.encoding :refer (decode-scope encode-scope as-query-string)]))

(def SESSION-ID "auth-session-id")

(defn wrap-schema-validation [h]
  (fn [req]
    (s/with-fn-validation
      (h req))))



(defn authorize-client [session component req store]


  (if-let [auth-interaction-session-result (get-result (:authenticator component) req)]
    ;; the session can be authenticated or maybe we are
    ;; coming from the authenticator workflow
    (do
      (debugf "auth session result is %s" auth-interaction-session-result)
      (if (:cylon/authenticated? auth-interaction-session-result)
        ;; "you are authenticated now!"

        (let [_ (clean-resources! (:authenticator component) req)
              code (str (java.util.UUID/randomUUID))
              {:keys [client-id requested-scopes]}
              session
              {:keys [redirection-uri
                      application-name
                      description
                      requires-user-acceptance
                      required-scopes] :as client}
              (lookup-client+ (:client-registry component) client-id)]

          (assoc-session! (:session-store component) (get-session-id req SESSION-ID) :cylon/authenticated? true)
          (assoc-session! (:session-store component) (get-session-id req SESSION-ID) :code code)

          ;; Remember the code for the possible exchange - TODO expire these
          (swap! store assoc
                 {:client-id client-id
                  :code code}
                 {:created (java.util.Date.)
                  :cylon/identity (:cylon/identity auth-interaction-session-result)})

          ;; When a user permits a client, the client's scopes that they have accepted, are stored in the user preferences database
          ;; why?
          ;; because next time, we don't have to ask the user for their permission everytime they login
          ;; ok, i understand
          ;; however

          (debugf (if requires-user-acceptance
                    "App requires user acceptance"
                    "App does not require user acceptance"))
          ;; Lookup the application - do we have at-least the client id?
          (if requires-user-acceptance
            {:status 200
             :body (html [:body
                          [:form {:method :post :action (path-for (:modular.bidi/routes req) ::permit)}
                           [:h1 "Authorize application?"]
                           [:p (format "An application (%s) is requesting to use your credentials" application-name)]
                           [:h2 "Application description"]
                           [:p description]
                           [:h2 "Scope"]
                           (for [s requested-scopes]
                             (let [s (apply str (interpose "/" (remove nil? ((juxt namespace name) s))))]
                               [:p [:label {:for s} s] [:input {:type "checkbox" :id s :name s :value s :checked true}]]))
                           [:input {:type "submit"}]]
                          ])}

            (do
              (debugf (format "App doesn't require user acceptance, granting scopes as required: [%s]" required-scopes))
              (swap! store update-in
                     [{:client-id client-id
                       :code code}]
                     assoc :granted-scopes required-scopes)
              ;; 4.1.2: "If the resource owner grants the
              ;; access request, the authorization server
              ;; issues an authorization code and delivers it
              ;; to the client by adding the following
              ;; parameters to the query component of the
              ;; redirection URI"
              (redirect
               (str redirection-uri
                    (as-query-string
                     {"code" code
                      "state"  (:state session)}))))))

        ;; you have auth-session although you are NOT authenticated but ,,, we carry on with this session"
        (do
          (debugf "Session exists, but no evidence in it of authentication. Initiating authentication interaction using %s" (:authenticator component))
          (initiate-authentication-interaction (:authenticator component) req {}))))

    ;; You are not authenticated, so let's authenticate first.
    (do
      (debugf "Not authenticated, must authenticate first with %s" (:authenticator component))
      (let [auth-session
            (create-session!
             (:session-store component)
             {:client-id (-> req :query-params (get "client_id"))
              :requested-scopes (decode-scope (-> req :query-params (get "scope")))
              :state (-> req :query-params (get "state"))
              :response-type  "code"})]
        (cookies-response-with-session
         (initiate-authentication-interaction (:authenticator component) req {})
         SESSION-ID auth-session)))))


(defrecord AuthorizationServer [store scopes iss]
  WebService
  (request-handlers [component]
    {::authorization-endpoint
     (-> (fn [req]
           ;; Establish whether the user-agent is already authenticated.
           ;; If not, create a session with client-id, scope and state
           ;; and redirect to the login form

           ;; TODO We should validate the incoming response_type
           ;; The trouble is, we lose all the query string information
           ;; if we initiate the auth interaction session.

           ;; (Can we do this in a go block however?)

           (debugf "OAuth2 authorization server: Authorizing request")

           (let [session (get-session-from-cookie req SESSION-ID (:session-store component))
                 {response-type "response_type" client-id "client_id"} (:query-params req)
                 r-t (or response-type (:response-type session))
                 ]
             (case r-t
               "code" (authorize-client
                       session
                       component req store)

               ;; Unknown response_type
               {:status 400
                :body (format "Bad response_type parameter: '%s'" response-type)}
               )
             ))
         wrap-params
         wrap-schema-validation)

     ;; TODO Implement RFC 6749 4.1.2.1 Error Response

     ::permit
     (->
      ;; TODO I'm worred about the fact we must ensure that the session
      ;; represents a true authenticated user
      (fn [req]
        (let [session (get-session-from-cookie req SESSION-ID (:session-store component))]
          (if (:cylon/authenticated? session)
            (let [permitted-scopes (set (map
                                         (fn [x] (apply keyword (str/split x #"/")))
                                         (keys (:form-params req))))
                  _ (debugf "permitted-scopes is %s" permitted-scopes)
                  requested-scopes (:requested-scopes session)
                  _ (debugf "requested-scopes is %s" requested-scopes)

                  granted-scopes (set/intersection permitted-scopes requested-scopes)
                  code (:code session)
                  client-id (:client-id session)
                  {:keys [redirection-uri] :as client} (lookup-client+ (:client-registry component) client-id)
                  ]

              (debugf "Granting scopes: %s" granted-scopes)
              (swap! store update-in
                     [{:client-id client-id
                       :code code}]
                     assoc :granted-scopes granted-scopes)

              (redirect
               (format "%s?code=%s&state=%s"
                       redirection-uri code (:state session)))))))

      wrap-params)

     ;; RFC 6749 4.1 (D) - and this is the Token endpoint as described
     ;; in section 3 (Protocol Endpoints)
     ::token-endpoint
     ;; This is initiated by the client
     (-> (fn [req]
           (let [params (:form-params req)
                 code (get params "code")
                 client-id (get params "client_id")
                 client (lookup-client+ (:client-registry component) client-id)]

             ;; "When making the request, the client authenticates with
             ;; the authorization server."
             (if (not= (get params "client_secret") (:client-secret client))
               {:status 403 :body "Client could not be authenticated"}

               (if-let [{identity :cylon/identity
                         granted-scopes :granted-scopes}
                        (get @store
                             ;; I don't think this key has to include client-id
                             ;; - it can just be 'code'.
                             {:client-id client-id :code code})]

                 (let [{access-token :cylon.session/key}
                       (create-session! (:access-token-store component)
                                        {:client-id client-id
                                         :identity identity
                                         :scopes granted-scopes})
                       claim {:iss iss
                              :sub identity
                              :aud client-id
                              :exp (plus (now) (days 1)) ; expiry
                              :iat (now)}]

                   (infof "Claim is %s" claim)

                   ;; 5.1 Successful Response

                   ;; " The authorization server issues an access token
                   ;; and optional refresh token, and constructs the
                   ;; response by adding the following parameters to the
                   ;; entity-body of the HTTP response with a 200 (OK)
                   ;; status code:"

                   (debugf "About to OK, granted scopes is %s (type is %s)" granted-scopes (type granted-scopes))

                   {:status 200
                    :body (encode {"access_token" access-token
                                   "token_type" "Bearer"
                                   "expires_in" 3600
                                   ;; TODO Refresh token (optional)

                                   ;; 5.1 scope OPTIONAL only if
                                   ;; identical to scope requested by
                                   ;; client, otherwise required. In
                                   ;; this way, we pass back the scope
                                   ;; to the client.
                                   "scope" (encode-scope granted-scopes)

                                   ;; OpenID Connect ID Token
                                   "id_token" (-> claim
                                                  jwt
                                                  (sign :HS256 "secret") to-str)

                                   })})
                 {:status 400
                  :body "Invalid request - unknown code"}))))
         wrap-params )})

  (routes [_]
    ["/" {"authorize" {:get ::authorization-endpoint}
          "permit-client" {:post ::permit}
          ;; TODO: Can we use a hyphen instead here?
          "access_token" {:post ::token-endpoint}}])

  (uri-context [_] "/login/oauth")

  AccessTokenAuthorizer
  (authorized? [component access-token scope]
    (if-not (contains? (set (keys scopes)) scope)
      (throw (ex-info "Scope is not a known scope to this authorization server"
                      {:component component
                       :scope scope
                       :scopes scopes}))
      (contains? (:scopes (get-session (:access-token-store component) access-token))
                 scope)))

  RequestAuthorizer
  (request-authorized? [component request scope]
    (when-let [auth-header (get (:headers request) "authorization")]
      ;; Only match 'Bearer' tokens for now
      (let [access-token (second (re-matches #"\QBearer\E\s+(.*)" auth-header))]
        (authorized? component access-token scope)))))

(defn new-authorization-server [& {:as opts}]
  (->> opts
       (merge {:store (atom {})})
       (s/validate
        {:scopes {s/Keyword {:description s/Str}}
         :store s/Any
         :iss s/Str             ; uri actually, see openid-connect ch 2.
         })
       map->AuthorizationServer
       (<- (component/using
            [:access-token-store
             :session-store
             :user-domain
             :client-registry
             :authenticator]))))
