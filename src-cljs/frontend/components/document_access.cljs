(ns frontend.components.document-access
  (:require [clojure.string :as str]
            [datascript :as d]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.models.doc :as doc-model]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

(defn permission-denied-overlay [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            doc-id (:document/id app)]
        (html
         [:div.menu-view
          [:div.menu-view-frame
           [:article
            [:h2 "This document is private"]
            (if (:cust app)
              (list
               [:p "Let the owner know you want to collaborate."]
               [:a.menu-button {:on-click #(cast! :permission-requested {:doc-id doc-id})
                                :role "button"}
                "Request access"])
              (list
               [:p "To check if you have access or to request access"]
               [:a.menu-button {:href (auth/auth-url)
                                :on-click #(do
                                             (.preventDefault %)
                                             (cast! :track-external-link-clicked
                                                    {:path (auth/auth-url)
                                                     :event "Signup Clicked"
                                                     :properties {:source "permission-denied-overlay"}}))
                                :role "button"}
                "Sign In"]))
            [:p "Anything you draw on this document will only be visible to you. "]
            [:p
             "If you want a document of your own, you can "
             [:a {:href "/" :target "_self"} "create your own document"]
             ". "]]]])))))

(defn manage-permissions-overlay [app owner]
  (reify
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (seq (filter #(or (= :document/privacy (:a %))
                                           (= :permission/document (:a %))
                                           (= :access-grant/document (:a %)))
                                      (:tx-data tx-report)))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))

    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            doc-id (:document/id app)
            doc (doc-model/find-by-id @db doc-id)
            private? (= :document.privacy/private (:document/privacy doc))
            permission-grant-email (get-in app state/permission-grant-email-path)
            permissions (ds/touch-all '[:find ?t :in $ ?doc-id :where [?t :permission/document ?doc-id]] @db doc-id)
            access-grants (ds/touch-all '[:find ?t :in $ ?doc-id :where [?t :access-grant/document ?doc-id]] @db doc-id)]
        (html
         [:div.menu-view
          [:div.menu-view-frame
           [:article
            [:label
             [:input {:type "checkbox"
                      :checked private?
                      :onChange #(cast! :document-privacy-changed
                                        {:doc-id doc-id
                                         :setting (if private?
                                                    :document.privacy/public
                                                    :document.privacy/private)})}]
             (if private?
               "Uncheck to make document public"
               "Check to make document private")]
            (when private?
              (list
               [:p "Grant access to a colleague"]
               [:form.menu-invite-form {:on-submit #(do (cast! :permission-grant-submitted)
                                                        false)
                                        :on-key-down #(when (= "Enter" (.-key %))
                                                        (cast! :permission-grant-submitted)
                                                        false)}
                [:input {:type "text"
                         :required "true"
                         :data-adaptive ""
                         :value (or permission-grant-email "")
                         :on-change #(cast! :permission-grant-email-changed {:value (.. % -target -value)})}]
                [:label {:data-placeholder "Teammate's Email"
                         :data-placeholder-nil "What's your teammate's email?"
                         :data-placeholder-forgot "Don't forget to submit."}]]
               (when (or (seq permissions)
                         (seq access-grants))
                 "People with access:")
               (for [p permissions]
                 [:div (:permission/cust p)])
               (for [a access-grants]
                 [:div (:access-grant/email a)])))]]])))))
