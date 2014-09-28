(ns frontend.components.aside
  (:require [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.state :as state]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn menu [app owner]
  (reify
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])
            show-grid? (get-in app state/show-grid-path)
            night-mode? (get-in app state/night-mode-path)]
       (html
         [:div.aside-menu
          [:button
           (common/icon :logomark-precursor)
           [:span "Precursor"]]
          [:button
           (common/icon :user)
           [:span "Login"]]
          [:button
           (common/icon :download)
           [:span "Download"]]
          [:div.aside-settings
           [:button
            (common/icon :settings)
            [:span "Settings"]]
          [:div.settings-menu
           [:button {:on-click #(put! controls-ch [:show-grid-toggled])}
            [:span "Show Grid"]
            (if show-grid?
              (common/icon :check)
              (common/icon :times))]
           [:button {:on-click #(put! controls-ch [:night-mode-toggled])}
            [:span "Night Mode"]
            (if night-mode?
              (common/icon :check)
              (common/icon :times))]
           [:button
            [:span "Settings"]
            (common/icon :times)]]]])))))
