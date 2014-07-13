;; Copyright © 2014, JUXT LTD. All Rights Reserved.

(ns cylon.user)

(defprotocol UserStore
  (get-user [_ identity])
  (store-user! [_ identity user-details]))

(defprotocol UserDomain
  (verify-user [_ identity password])
  (add-user! [_ identity password user-details]))
