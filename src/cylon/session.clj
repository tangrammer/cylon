;; Copyright © 2014, JUXT LTD. All Rights Reserved.

(ns cylon.session
  (:require
   [ring.middleware.cookies :refer (cookies-request cookies-response)]
   [cylon.session.protocols :as p]
   [cylon.util :refer (absolute-uri)]
   [schema.core :as s]))

;; Old functions here

(s/defschema Request "A Ring-style request"
  {:headers s/Any
   s/Keyword s/Any})

(s/defschema Response "A Ring-style response"
  {(s/optional-key :status) s/Num
   (s/optional-key :headers) s/Any
   (s/optional-key :body) s/Str})

(s/defn session :- (s/maybe{s/Keyword s/Any})
  [component :- (s/protocol p/SessionStore)
   request :- Request]
  (p/session component request))

;;(s/defn new-session-response :- )

(s/defn assoc-session-data! :- nil
  [component :- (s/protocol p/SessionStore)
   request :- Request
   m :- {s/Keyword s/Any}]
  (p/assoc-session-data! component request m))

(s/defn respond-with-new-session! :- Response
  [component :- (s/protocol p/SessionStore)
   request :- Request
   data :- {s/Keyword s/Any}
   response :- Response]
  (p/respond-with-new-session! component request data response))

(s/defn respond-close-session! :- Response
  [component :- (s/protocol p/SessionStore)
   request :- Request
   response :- Response]
  (p/respond-close-session! component request response))

#_(defn get-session-id [request cookie-name]
  (-> request cookies-request :cookies (get cookie-name) :value))

#_(s/defn get-session-from-cookie :- (s/maybe {:cylon.session/key s/Str
                                             :cylon.session/expiry s/Num
                                             s/Keyword s/Any})
  [request
   cookie-name :- s/Str
   session-store :- (s/protocol SessionStore)]
  (get-session session-store (get-session-id request cookie-name)))

#_(defn get-session-value [request cookie-name session-store k]
    (get (get-session-from-cookie request cookie-name session-store) k))


(defn new-xession [req session-store]
  (let [store (atom {})]
    (reify p/Xession
      (is-new? [this]
        (empty? (p/xession this)))
      (xession [_]
        (merge @store (session session-store req)))
      (assoc-xession-data! [_ data]
        (swap! store merge data))
      (persist! [this response]
        (if (session session-store req)
          (do
            (assoc-session-data! session-store req @store)
            response)
          (respond-with-new-session! session-store req @store response))
        ))))


(defn wrap-require-session-adv
  [handler-with-session session-store should-exist-session?]
  (fn [req]
    (let [xess (new-xession req session-store)]
      (if (and (p/is-new? xess) should-exist-session?)
        ;; TODO: it should be better to redirect to /home ???
        (throw (Exception. (format "should exist-session in: %s"
                                   (absolute-uri req))))
        (let [response (handler-with-session req xess)]
          (p/persist! xess response))))))


(defn wrap-require-session
  [handler session-store should-exist-session?]
  (fn [req]
    (let [session (session session-store req)]
      (if-not session
        (if should-exist-session?
          ;; TODO: it should be better to redirect to /home ???
          (throw (Exception. (format "should exist-session in: %s"
                                     (absolute-uri req))))
          (let [response (handler req)
                session-data (:cylon-session-data response)]
            (respond-with-new-session! session-store req (or session-data {}) response)))
        (let [req-with-session (assoc req :session session)
              response (handler req-with-session)]
          (when-let [session-data (:cylon-session-data response)]
            (assoc-session-data! session-store req session-data))
          response)))))

(defn response-with-data-session [response session-state]
  (merge {:cylon-session-data session-state} response))
