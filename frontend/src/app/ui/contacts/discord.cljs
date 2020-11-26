;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.contacts.discord
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.events :as ev]
   [app.events.messages :as em]
   [app.store :as st]
   [app.ui.forms :as fm]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.util.i18n :as i18n :refer [t tr]]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(defn prepare-submit-data
  [form]
  {:id (get-in form [:clean-data :id])
   :is-paused (get-in form [:clean-data :is-paused])
   :name (get-in form [:clean-data :name])
   :uri (get-in form [:clean-data :uri])})

(s/def ::discord-contact-form
  (s/keys :req-un [::us/uri ::name]))

(mf/defc discord-contact-modal
  {::mf/register modal/components
   ::mf/register-as :discord-contact}
  [{:keys [id] :as props}]
  (let [on-close    (mf/use-fn (st/emitf (modal/hide)))
        contact-ref (mf/use-memo (mf/deps id) #(st/contact-ref id))
        contact     (mf/deref contact-ref)


        on-success
        (mf/use-callback
         (st/emitf
          (modal/hide)
          (em/show {:content "Contact created succesfully!"
                    :type :success
                    :timeout 5000})))

        on-error
        (mf/use-callback
         (fn [{:keys [code type] :as error}]
           (cond
             (and (= type :validation)
                  (= code :contact-already-exists))
             (st/emit! (em/show {:content "Contact already exist!"
                                 :type :error}))

             (and (= type :validation)
                  (= code :contact-limits-reached))
             (st/emit! (em/show {:content "Can't create more contacts. Limit reached"
                                 :type :warning}))

             :else
             (rx/throw error))))

        on-submit
        (mf/use-callback
         (fn [form]
           (let [params (prepare-submit-data @form)
                 params (with-meta params
                          {:on-success on-success
                           :on-error on-error})]
             (if contact
               (st/emit! (ev/update-contact params))
               (st/emit! (ev/create-discord-contact params))))))

        initial
        (mf/use-memo
         (mf/deps contact)
         (fn []
           (if contact
             {:id (:id contact)
              :name (:name contact)
              :is-paused (:is-paused contact)
              :uri (get-in contact [:params :uri])}
             {:is-paused false})))

        form (fm/use-form :spec ::discord-contact-form
                          :initial initial)]

    [:div.modal-overlay
     [:div.modal.contact-modal.form-container
      [:& fm/form {:on-submit on-submit :form form}
       [:div.modal-header
        [:div.modal-header-title
         (if contact
           [:h2 "Update Discord Webhook"]
           [:h2 "Create Discord Webhook"])]
        [:div.modal-close-button
         {:on-click on-close} i/times]]

       [:div.modal-content
        [:div.form-row
         [:& fm/input
          {:name :name
           :type "text"
           :label "Label"}]]

        [:div.form-row
         [:& fm/input
          {:name :uri
           :type "text"
           :disabled (boolean contact)
           :label "Webhook URI"}]]]

       [:div.modal-footer
        [:div.action-buttons
         [:& fm/submit-button {:label "Submit"}]]]]]]))
