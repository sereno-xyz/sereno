;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitors.healthcheck-form
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.events.messages :as em]
   [app.events :as ev]
   [app.repo :as rp]
   [app.store :as st]
   [app.ui.forms :as fm]
   [app.ui.icons :as i]
   [app.ui.monitors.http-form :refer [tags-input contacts-input]]
   [app.ui.modal :as modal]
   [app.util.dom :as dom]
   [app.util.time :as dt]
   [app.util.i18n :refer [tr]]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(mf/defc left-column
  [{:keys [profile] :as props}]
  [:div.column
   [:div.form-row
    [:& fm/input
     {:name :name
      :type "text"
      :label "Label:"}]]

   [:& tags-input]
   [:& contacts-input]])

(mf/defc right-column
  []
  [:div.column
   [:div.form-row
    [:& fm/time-range
     {:name :cadence
      :label "Period:"}]]

   [:div.form-row
    [:& fm/time-range
     {:name :grace-time
      :label "Grace time:"}]]])

(defn- on-error
  [form err]
  (cond
    (and (= :validation (:type err))
         (= :monitor-limits-reached (:code err)))
    (rx/of (ev/show-message {:content "Monitors limits reached."
                             :type :error
                             :timeout 3000}))

    (and (= :validation (:type err))
         (= :cadence-limits-reached (:code err)))
    (do
      (swap! form assoc-in [:errors :cadence]
             {:message "Cadence not allowed."})
      (rx/empty))

    :else
    (rx/throw err)))

(defn- on-success
  [form]
  (st/emit! (modal/hide))
  (if (get-in @form [:data :id])
    (st/emit! (em/show {:content "Monitor updated"
                        :timeout 2000
                        :type :success}))
    (st/emit! (em/show {:content "Monitor created"
                        :timeout 2000
                        :type :success}))))

(defn- on-submit
  [form]
  (let [params (d/remove-nil-vals (:clean-data @form))
        params (with-meta params
                 {:on-success (partial on-success form)
                  :on-error (partial on-error form)})]
    (if (get-in @form [:data :id])
      (st/emit! (ptk/event :update-healthcheck-monitor params))
      (st/emit! (ptk/event :create-healthcheck-monitor params)))))

(s/def ::type #{"healthcheck"})
(s/def ::name ::us/not-empty-string)
(s/def ::grace-time ::us/integer)
(s/def ::cadence ::us/integer)
(s/def ::contacts (s/coll-of ::us/uuid :min-count 1))
(s/def ::tags (s/coll-of ::us/string :kind set?))

(s/def ::monitor-form
  (s/keys :req-un [::name ::type ::cadence ::contacts]
          :opt-un [::id ::tags]))

(mf/defc monitor-form
  [{:keys [item on-error] :as props}]
  (let [profile    (mf/deref st/profile-ref)
        cancel-fn  (st/emitf (modal/hide))
        params     (:params item)

        initial
        (mf/use-memo
         (mf/deps item)
         (fn []
           (if item
             {:id         (:id item)
              :name       (:name item)
              :type       (:type item)
              :cadence    (:cadence item)
              :grace-time (:grace-time params)
              :contacts   (:contacts item #{})
              :tags       (or (:tags item) #{})}
             {:cadence (* 60 60 24)
              :grace-time (* 60 60)
              :type "healthcheck"
              :contacts #{}})))

        form (fm/use-form :spec ::monitor-form
                          :initial initial)]

    [:div.modal.monitor-form-modal.form-container
     [:& fm/form {:on-submit on-submit :form form}
      [:div.modal-header
       [:div.modal-header-title
        (if item
          [:h2 "Update healthcheck/s monitor:"]
          [:h2 "Create healthcheck/s monitor"])]
       [:div.modal-close-button
        {:on-click cancel-fn} i/times]]

      [:div.modal-content.columns
       [:& left-column {:profile profile}]
       [:& right-column]]

      [:div.modal-footer
       [:div.action-buttons
        [:& fm/submit-button {:form form :label "Submit"}]]]]]))

(mf/defc healthcheck-monitor-form-modal
  {::mf/wrap-props false
   ::mf/register modal/components
   ::mf/register-as :healthcheck-monitor-form}
  [props]
  (let [on-close (st/emitf (modal/hide))]
    [:div.modal-overlay
     [:> monitor-form props]]))
