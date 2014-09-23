(ns cylon.util)

(defprotocol KorksSet
  (as-set [_]))

(extend-protocol KorksSet
  clojure.lang.Keyword
  (as-set [k] #{k})
  clojure.lang.PersistentHashSet
  (as-set [ks] ks)
  clojure.lang.PersistentVector
  (as-set [v] (set v))
  clojure.lang.PersistentList
  (as-set [l] (set l)))

(defn absolute-uri [req]
  (apply format "%s://%s%s"
         ((juxt (comp name :scheme)
                (comp #(get % "host") :headers)
                :uri)
          req)))

(defn get-original-uri [req]
  (str (:uri req)
       (when-let [qs (:query-string req)] (when (not-empty qs) (str "?" qs )))))
