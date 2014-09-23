(ns cylon.oauth.impl.boilerplate
  (:require
   [com.stuartsierra.component :as component]
   [cylon.session :refer (session)]
   [hiccup.form :as hf]
   [hiccup.page :refer (html5)]
   [modular.bidi :refer (WebService path-for)]
   [modular.bootstrap :refer (ContentBoilerplate)]
   [plumbing.core :refer (<-)]
   [schema.core :as s]
   ))

(defn displayed? [menu user]
  (case (:security menu)
    :user user
    :all true
    :none (nil? user)
    true))
(defn menus [req]
    [{:label "Sign up"
     :security :none
     :location :navbar
     :target (path-for req :cylon.signup.signup/GET-signup-form)}
     {:label "Account"
      :security :user
      :location :navbar
      :target "#"
      :children [{:label "Reset Password"
                  :security :user
                  :target "/reset-password"}
                 ]}
     {:label "Login"
      :security :none
      :location :navbar
      :target (path-for req :cylon.impl.authentication/GET-login-form)}
     {:label "Logout"
      :security :user
      :location :navbar
      :target (path-for req :cylon.oauth.impl.authorization-server/logout)}])

(defn auth-base-page [{:keys [title]} req user body & scr]
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"}]
    [:meta {:property "dc:language" :content "en"}]
    [:meta {:property "dc:title" :content title}]
   ; [:meta {:name "description" :content "The operating system for the Internet of Things. opensensors.IO enables you to create real time IOT applications in minutes"}]
    ;[:meta {:name "keywords" :content "IOT, IoT, Internet of Things, web of things, smart things, internet of everything, smart systems, smart cities, future cities, smart environments, devices, sensors, open sensors, citizen science, open, arm, open data, open source, mbed, ti, texas instruments, mqtt, ble, blue tooth, raspberry pi, arduino, CoAP, analytics, big data, analytics"}]
    ;; TODO Provide a local resource for offline dev...
    [:link {:href "//netdna.bootstrapcdn.com/font-awesome/4.1.0/css/font-awesome.min.css" :rel "stylesheet"}]
    [:title title]
    [:link {:href "/css/bootstrap.min.css" :rel "stylesheet"}]
    [:link {:href "/css/style.css" :rel "stylesheet"}]
    [:script {:src "/js/jquery.min.js"}]
    [:script {:src "/js/bootstrap.min.js"}]
    [:script {:src "/js/jquery.session.js"}]]
   [:body
    [:div#wrap
     [:nav {:class "navbar navbar-default" :role "navigation"}
      [:div.container-fluid
       [:div.navbar-header
        [:button.navbar-toggle {:type "button" :data-toggle "collapse" :data-target "#bs-example-navbar-collapse-1"}
         [:span.sr-only "Toggle navigation"]
         [:span.icon-bar]
         [:span.icon-bar]
         [:span.icon-bar]]
        [:a#home-logo.navbar-brand {:href (path-for req :cylon.signup.signup/GET-user-account)} title]]
       [:div {:class "collapse navbar-collapse" :id "bs-example-navbar-collapse-1"}
        [:ul {:class "nav navbar-nav navbar-right"}
         (for [menu (menus req)
               :when (displayed? menu user)]
           (if (= :navbar (:location menu))
             (if (:children menu)
               [:li.dropdown [:a.dropdown-toggle {:href "#" :data-toggle "dropdown"} (:label menu) [:b.caret]]
                [:ul.dropdown-menu
                 (for [child (:children menu)
                       :when (displayed? child user)]
                   [:li [:a {:href (:target child)} (:label child)]])]]
               [:li [:a {:href (:target menu)} (:label menu)]]
               )))
         (when user
           [:li [:a user]]
           [:script (format "$.session.set('user', '%s')" user)])]]]]
     #_(if user
       [:div#user-page.row
        [:div.col-sm-2
         [:div.sidebar-nav
          (side-menu user req)]]
        [:div.col-xs-9
         body]]
       body)
     body
     ]

     ;;cljs
    [:script {:src "/js/react.js"}]
    [:script {:src "/cljs/cljs.js"}]
    [:script {:src "/cljs/azondi.js"}]
    [:script {:src "/cljs/cljs.js"}]
    [:script {:src "/cljs/azondi.js"}]
    [:script {:src "/cljs/logo.js"}]
    [:script {:src "/cljs/view.js"}]
    [:script {:src "/cljs/topic-browser.js"}]
    [:script {:src "/js/helpers.js"}]
    [:div#footer {:class "navbar-default navbar-fixed-bottom"}
     #_[:div.row
      [:div.col-xs-3
       [:h3 "opensensors.io"]
       [:div#copyright
        "&copy; 2014 open sensors ltd"]
       [:a {:href "/terms"} "Terms"]]
      [:div.col-xs-3
       [:h3 "Company"]
       [:a {:href "/about"} "About Us"] [:br]
       [:a {:href "http://blog.opensensors.IO"} "Blog"] [:br]
       [:a {:href "/careers"} "Careers"]]
      [:div.col-xs-3
       [:h3 "Help"]
       [:a {:href "/help"} "Getting Started"]]
      [:div.col-xs-3
       [:h3 "Connect"]
       [:a {:href "https://twitter.com/opensensorsio"} "Twitter"] [:br]
       [:a {:href "mailto:hello@opensensors.io?subject=website%20enquiry"} "Mail"] [:br]
       [:a {:href "http://blog.opensensors.IO"} "Blog"]]]]

    ;; extenal libs
    scr])

  )

(defrecord AuthBasePageContentBoilerplate [session-store]
  ContentBoilerplate
  (wrap-content-in-boilerplate [this req content]
    (let [session (session session-store req)
          subject (:cylon/identity session)]

      (auth-base-page this req subject content)))
  )

(defn new-auth-basepage-content-boilerplate [& {:as opts}]
  (->> opts
       (merge {:title "CYLON AUTH-SERVER"})
       (s/validate
        {:title s/Str})
       map->AuthBasePageContentBoilerplate
       (<- (component/using  [:session-store]))))
