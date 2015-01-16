(ns frontend.sente
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require [cljs.core.async :as async :refer (<! >! put! chan)]
            [clojure.set :as set]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [taoensso.sente  :as sente :refer (cb-success?)]
            [frontend.datascript :as ds]
            [datascript :as d]))

(defn send-msg [sente-state message & [timeout-ms callback-fn :as rest]]
  (if (-> sente-state :state deref :open?)
    (apply (:send-fn sente-state) message rest)
    (let [watch-id (utils/uuid)]
      ;; TODO: handle this in the handle-message fn below
      (add-watch (:state sente-state) watch-id
                 (fn [key ref old new]
                   (when (:open? new)
                     (apply (:send-fn sente-state) message rest)
                     (remove-watch ref watch-id)))))))

(defn subscribe-to-document [sente-state document-id & {:keys [requested-color]}]
  (send-msg sente-state [:frontend/subscribe {:document-id document-id
                                              :requested-color requested-color}]))

(defn fetch-subscribers [sente-state document-id]
  (send-msg sente-state [:frontend/fetch-subscribers {:document-id document-id}] 10000
            (fn [data]
              (put! (:ch-recv sente-state) [:chsk/recv [:frontend/fetch-subscribers data]]))))

(defmulti handle-message (fn [app-state message data]
                           (utils/mlog "handle-message" message data)
                           message))

(defmethod handle-message :default [app-state message data]
  (println "ws message" (pr-str message) (pr-str data)))

(defmethod handle-message :datomic/transaction [app-state message data]
  (let [datoms (:tx-data data)]
    (d/transact! (:db @app-state)
                 (map ds/datom->transaction datoms)
                 {:server-update true})))

(defmethod handle-message :frontend/subscriber-joined [app-state message data]
  (swap! app-state update-in [:subscribers (:client-id data)] merge (dissoc data :client-id)))

(defmethod handle-message :frontend/subscriber-left [app-state message data]
  (swap! app-state update-in [:subscribers] dissoc (:client-id data)))

(defmethod handle-message :frontend/mouse-move [app-state message data]
  (swap! app-state utils/update-when-in [:subscribers (:client-id data)] merge (select-keys data [:mouse-position :tool :layers])))

(defmethod handle-message :frontend/update-subscriber [app-state message data]
  (swap! app-state update-in [:subscribers (:client-id data)] merge (:subscriber-data data)))

(defmethod handle-message :frontend/db-entities [app-state message data]
  (when (= (:document/id data) (:document/id @app-state))
    (d/transact! (:db @app-state)
                 (:entities data)
                 {:server-update true})))

(defmethod handle-message :frontend/invite-response [app-state message data]
  (let [doc-id (:document/id data)
        response (:response data)]
    (swap! app-state update-in (state/invite-responses-path doc-id) conj response)))

(defmethod handle-message :frontend/subscribers [app-state message {:keys [subscribers] :as data}]
  (when (= (:document/id data) (:document/id @app-state))
    (swap! app-state update-in [:subscribers] (fn [s]
                                                (merge-with merge
                                                            subscribers
                                                            s)))))

(defmethod handle-message :frontend/error [app-state message data]
  (put! (get-in @app-state [:comms :errors]) [:document-permission-error data])
  (utils/inspect data))

(defmethod handle-message :chsk/state [app-state message data]
  (let [state @app-state]
    (when (and (:open? data)
               (not (:first-open? data))
               (:document/id state))
      ;; TODO: This seems like a bad place for this. Can we share the same code that
      ;;       we use for subscribing from the nav channel in the first place?
      (subscribe-to-document
       (:sente state) (:document/id state)
       :requested-color (get-in state [:subscribers (:client-id state) :color])))))


(defn do-something [app-state sente-state]
  (let [tap (async/chan (async/sliding-buffer 10))
        mult (:ch-recv-mult sente-state)]
    (async/tap mult tap)
    (go-loop []
      (when-let [{[type data] :event :as stuff} (<! tap)]
        (case type
          :chsk/recv (utils/swallow-errors
                      (let [[message message-data] data]
                        (handle-message app-state message message-data)))

          ;; :chsk/state is sent when the ws is opened or closed
          :chsk/state (utils/swallow-errors
                       (handle-message app-state type data))

          nil)
        (recur)))))

(defn init [app-state]
  (let [{:keys [chsk ch-recv send-fn state] :as sente-state}
        (sente/make-channel-socket! "/chsk" {:type :auto
                                             :chsk-url-fn (fn [& args]
                                                            (str (apply sente/default-chsk-url-fn args) "?tab-id=" (:tab-id @app-state)))})]
    (swap! app-state assoc :sente (assoc sente-state :ch-recv-mult (async/mult ch-recv)))
    (do-something app-state (:sente @app-state))))
