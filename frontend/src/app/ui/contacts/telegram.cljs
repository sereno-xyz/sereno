;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.contacts.telegram
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
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
   [promesa.core :as p]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   ["qrcode" :as qrcode]))

(declare telegram-link-info)

(defn prepare-submit-data
  [form]
  {:id (get-in form [:clean-data :id])
   :is-paused (get-in form [:clean-data :is-paused])
   :name (get-in form [:clean-data :name])})

(s/def ::telegram-contact-form
  (s/keys :req-un [::name]))

(mf/defc telegram-contact-modal
  {::mf/register modal/components
   ::mf/register-as :telegram-contact}
  [{:keys [id] :as props}]
  (let [on-close    (mf/use-fn (st/emitf (modal/hide)))
        contact-ref (mf/use-memo (mf/deps id) #(st/contact-ref id))
        contact     (mf/deref contact-ref)

        on-create-success
        (mf/use-callback
         (fn [contact]
           (st/emit!
            (modal/hide)
            (em/show {:content "Contact created succesfully!"
                      :type :success
                      :timeout 5000})
            (modal/show {:type :telegram-contact
                         :id (:id contact)}))))


        on-update-success
        (mf/use-callback
         (fn [contact]
           (st/emit!
            (modal/hide)
            (em/show {:content "Contact updated."
                      :type :info
                      :timeout 3000}))))

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
           (let [params (prepare-submit-data @form)]
             (if contact
               (st/emit! (ev/update-contact
                          (with-meta params
                            {:on-success on-update-success
                             :on-error on-error})))
               (st/emit! (ev/create-telegram-contact
                          (with-meta params
                            {:on-success on-create-success
                             :on-error on-error})))))))

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

        form (fm/use-form :spec ::telegram-contact-form
                          :initial initial)]

    [:div.modal-overlay
     [:div.modal.contact-modal.form-container
      [:& fm/form {:on-submit on-submit :form form}
       [:div.modal-header
        [:div.modal-header-title
         (if contact
           [:h2 "Update Telegram Webhook"]
           [:h2 "Create Telegram Webhook"])]
        [:div.modal-close-button
         {:on-click on-close} i/times]]

       [:div.modal-content
        [:div.form-row
         [:& fm/input
          {:name :name
           :type "text"
           :label "Label"}]]

        (when (:id contact)
          [:& telegram-link-info {:contact contact}])]

       [:div.modal-footer
        [:div.action-buttons
         [:& fm/submit-button {:label "Submit"}]]]]]]))

(mf/defc telegram-link-info
  [{:keys [contact]}]
  (let [params   (:params contact)
        mainopt  (mf/use-state "start")
        link-uri (str "https://t.me/sereno_bot?" @mainopt "=" (:short-id contact))
        qr-img   (mf/use-state nil)]
    (mf/use-layout-effect
     (mf/deps link-uri)
     (fn []
       (when-not (:validated-at contact)
         (p/then (qrcode/toDataURL link-uri #js {:margin 1})
                 (fn [data-url]
                   (reset! qr-img data-url))))))

    [:div.telegram-link-info
     (if (and (:chat-id params)
              (:validated-at contact))
       [:*
        [:p "This contact is linked with: "]
        [:ul
         [:li [:strong "Chat Type: "] " " (:chat-type params)]
         [:li [:strong "Chat ID: "] " " (:chat-id params)]
         [:li [:strong "Chat Title: "] " " (:chat-title params)]]]

       [:*
        [:p "This contact is " [:strong "not activated"] ". To
             activate it, you need to link it to some private or a
             group chat. Once the contact is linked, you will be able
             to send notifications to it. Once linked, the link can't
             be changed."]

        [:div.qrcode
         [:div.options
          [:span {:on-click #(reset! mainopt "start")
                  :class (dom/classnames :active (= @mainopt "start"))}
           "Chat"]
          [:span {:on-click #(reset! mainopt "startgroup")
                  :class (dom/classnames :active (= @mainopt "startgroup"))}
           "Group"]]
         (when @qr-img
           [:img {:src @qr-img}])
         [:div.link
          [:a {:href link-uri :target "_blank"} link-uri]]]])]))

