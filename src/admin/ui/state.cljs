(ns admin.ui.state
  (:require [om.core          :as om]
            [uri.core         :as uri]
            [siren.core       :as siren]
            [admin.xhr        :as xhr]
            [admin.ui.loading :as loading]
            [admin.ui.entity  :as entity]
            [admin.ui.entity.util :as util]
            [admin.ui.action  :as action]
            [admin.ui.history :as history]
            [admin.ui.login   :as login]))

(declare present!)
(declare reload!)
(declare on-entity-ok!)

(def http-ok         200)
(def http-created    201)
(def http-no-content 204)

(defn on-response! [cursor res]
  (condp = (.getStatus res)
    http-ok         (on-entity-ok! cursor (xhr/->edn res))
    http-created    (history/goto! (.getResponseHeader res "Location"))
    http-no-content (history/goto! (siren/self (:entity @cursor)))
    (js/alert "Server error: please refresh the page and try again.")
    ))

(defn auth-req [cursor opts]
  (let [{:keys [username password]} (:auth @cursor)]
   (xhr/req
    (update-in opts [:headers]
      #(assoc % "Authorization"
        (str "Basic " (js/btoa (str username ":" password))))))))

(defn exec-action!
  ([cursor action]
   (auth-req cursor
    {:method (:method action)
     :url    (:href action)
     :on-complete
     (fn [res ev]
       (if-let [listing-href (siren/get-link (:entity @cursor) "listing")]
         (history/goto! listing-href)
         (reload! cursor)))}))

  ([cursor action values]
   (if (= "GET" (:method action))
     (history/goto! (uri/add-query (:href action) values))
     (auth-req cursor
      {:method (:method action)
       :url    (:href action)
       :data   (pr-str values)
       :headers {"Content-Type" "application/edn"}
       :on-complete
       (fn [res ev] (on-response! cursor res))}))))

(defn show-action-form! [cursor act]
  (om/update! cursor :form
    {:action act
     :values {}
     :back-href (str "#" (:entity-url @cursor))
     :on-submit
     (fn [action values]
       (exec-action! cursor @action @values))}))

(defn set-pending-action! [cursor act-name]
  (om/update! cursor :pending-action act-name))

(defn do-action! [cursor act-name]
  (when-let [act (siren/get-action (:entity @cursor) act-name)]
    (show-action-form! cursor act)))

(defn do-pending-action! [cursor]
  (do-action! cursor (:pending-action @cursor))
  (om/update! cursor :pending-action nil))

(defn clear-current-action! [cursor]
  (om/update! cursor :form nil))

(defn update-location! [cursor url]
  (om/update! cursor :url url) ;; pre-set url so no action is taken onhashchange
  (history/goto! url))

(defn goto-action-form! [cursor ent act]
  (let [url (util/action->href ent act)]
    (update-location! cursor url)
    (show-action-form! cursor act)))

(defn update-all-in
  ([m ks f & args]
   (update-in m ks (fn [m] (map #(apply f % args) m)))))

(defn add-action-handler [act ent cursor]
  (assoc act :on-exec
    (if (:fields act)
      #(goto-action-form! cursor ent act)
      #(exec-action! cursor act)
      )))

(defn add-action-handlers [ent cursor]
  (update-all-in ent [:actions] add-action-handler ent cursor))

(defn add-handlers [ent cursor]
  (-> ent
      (update-all-in [:entities] add-action-handlers cursor)
      (add-action-handlers cursor)))

(defn on-entity-ok! [cursor ent]
  (let [self (util/->href ent)]
    (when (not= self (uri/base (:entity-url @cursor))) ;; FIXME is uri/base needed here?
      (om/update! cursor :entity-url self)
      (update-location! cursor self)))

  (let [ent+ (add-handlers ent cursor)]
   (om/update! cursor :entity ent+)
   (clear-current-action! cursor)
   (do-pending-action! cursor)))

(defn get-entity! [cursor href]
  (loading/begin-loading! cursor)
  (xhr/req
   {:method "GET"
    :url href
    :on-complete
    (fn [res ev]
      (when (not (xhr/aborted? res))
        (om/update! cursor :current-request nil)
        (loading/finish-loading! cursor)
        (on-response! cursor res)))}))

(defn load-entity! [cursor href]
  (om/update! cursor :entity-url href)
  (when-let [req (:current-request @cursor)]
    (.abort req))
  (let [req (get-entity! cursor href)]
    (om/update! cursor :current-request req)))

(defn reload! [cursor]
  (load-entity! cursor (:entity-url @cursor)))

(defn make-login-handler [cursor]
  (fn [login-cursor]
    (loading/begin-loading! login-cursor)
    (auth-req cursor
      {:method "GET"
       :url "/"
       :on-complete
       (fn [res ev]
         (loading/finish-loading! login-cursor)
         (let [status (.getStatus res)]
           (if (and (>= status 200)
                    (<  status 300))
             (login/login! login-cursor)
             (do
               (login/clear-password! login-cursor)
               (js/alert "Sign in failed: please check username and password")))))})))

(defn init! [cursor]
  (om/transact! cursor :auth #(or % {}))
  (login/login-from-localstorage! (:auth cursor)))

(defn present! [cursor href]
  (when (not= href (:url @cursor))
    (let [[base frag] (uri/split-fragment href)]
      (om/update! cursor :url href)
      (set-pending-action! cursor frag)
      (load-entity! cursor base))))
