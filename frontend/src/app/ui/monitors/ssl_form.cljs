;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitors.ssl-form
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.events :as ev]
   [app.repo :as rp]
   [app.store :as st]
   [app.ui.forms :as fm]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.ui.monitors.http-form :refer [tags-input contacts-input monitor-test]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(defn- prepare-submit-data
  [form]
  (let [cdata   (:clean-data form)
        bparams (select-keys cdata [:id :type :name :tags :contacts])
        mparams (select-keys cdata [:alert-before :uri])]
    (assoc (d/remove-nil-vals bparams)
           :params (d/remove-nil-vals mparams))))

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
    [:& fm/input
     {:type "text"
      :placeholder "https://example.com"
      :name :uri
      :label "URI:"}]]

   [:div.form-row
    [:& fm/input
     {:label "Alert before (days):"
      :type "number"
      :min 1
      :max 30
      :name :alert-before}]]])

(defn- on-error
  [form err]
  (cond
    (and (= :validation (:type err))
         (= :monitor-limits-reached (:code err)))
    (rx/of (ev/show-message {:content "Monitors limits reached."
                             :type :error
                             :timeout 3000}))

    :else
    (rx/throw err)))

(defn- on-submit
  [form]
  (let [params (prepare-submit-data @form)
        params (with-meta params
                 {:on-success  #(modal/hide!)
                  :on-error (partial on-error form)})]
    (if (get-in @form [:data :id])
      (st/emit! (ptk/event :update-ssl-monitor params))
      (st/emit! (ptk/event :create-ssl-monitor params)))))

(s/def ::type #{"ssl"})
(s/def ::name ::us/not-empty-string)
(s/def ::contacts (s/coll-of ::us/uuid :min-count 1))
(s/def ::uri ::us/uri)
(s/def ::tags (s/coll-of ::us/string :kind set?))
(s/def ::alert-before (s/and ::us/integer pos?))

(s/def ::monitor-form
  (s/keys :req-un [::name ::type::contacts ::uri ::alert-before]
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
             {:id       (:id item)
              :name     (:name item)
              :type     (:type item)
              :contacts (:contacts item #{})
              :tags     (or (:tags item) #{})
              :uri      (:uri params)}
             {:alert-before 5
              :type "http"
              :contacts #{}})))

        form (fm/use-form :spec ::monitor-form
                          :initial initial)]

    [:div.modal.monitor-form-modal.form-container.ssl-monitor
     [:& fm/form {:on-submit on-submit :form form}
      [:div.modal-header
       [:div.modal-header-title
        (if item
          [:h2 "Update SSL Certificate monitor"]
          [:h2 "Create SSL Certificate monitor"])]
       [:div.modal-close-button
        {:on-click cancel-fn} i/times]]

      [:div.modal-content.columns
       [:& left-column {:profile profile}]
       [:& right-column]]

      [:div.modal-footer
       [:div.action-buttons
        [:& fm/submit-button {:form form :label "Submit"}]
        [:& monitor-test {:form form :prepare-fn prepare-submit-data}]]]]]))

(mf/defc ssl-monitor-form-modal
  {::mf/wrap-props false
   ::mf/register modal/components
   ::mf/register-as :ssl-monitor-form}
  [props]
  (let [on-close (st/emitf (modal/hide))]
    [:div.modal-overlay
     [:> monitor-form props]]))
