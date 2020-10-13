;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitor-form
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
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(let [header-re #"([\w\d_\-]+)\:\s*([^\n]+)"
      conform   (fn [s]
                  (cond
                    (s/valid? (s/map-of string? string?) s)
                    s

                    (string? s)
                    (let [items (re-seq header-re s)]
                      (cond
                        (empty? s)
                        {}

                        (nil? items)
                        ::s/invalid

                        :else
                        (reduce (fn [acc [a b c]]
                                  (assoc acc (str/lower b) c))
                                {}
                                items)))
                    :else
                    ::s/invalid))
      unform   (fn [s]
                 (reduce-kv (fn [base k v]
                              (if (empty? base)
                                (str k ": " v)
                                (str base "\n" k ": " v)))
                            ""
                            s))]
  (s/def ::headers-map (s/conformer conform unform)))

(def cadence-options
  [{:value "60"    :label "1 minute"}
   {:value "120"   :label "2 minutes"}
   {:value "300"   :label "5 minutes"}
   {:value "1800"  :label "30 minutes"}
   {:value "3600"  :label "1 hour"}
   {:value "7200"  :label "2 hours"}
   {:value "21600" :label "6 hours"}
   {:value "43200" :label "12 hours"}])

(defn get-cadence-options
  [{:keys [limits-min-cadence] :as profile}]
  (let [min-cadence (or limits-min-cadence 300)]
    (filter (fn [{:keys [value] :as item}]
              (or (nil? value)
                  (<= min-cadence (d/parse-integer value))))
            cadence-options)))

(defmulti prepare-submit-data (fn [form] (get-in form [:data :type])))
(defmethod prepare-submit-data :default [_] (ex/raise :type :not-implemented))

(defmethod prepare-submit-data "http"
  [form]
  (let [bparams {:id (get-in form [:clean-data :id])
                 :type (get-in form [:clean-data :type])
                 :name (get-in form [:clean-data :name])
                 :tags (get-in form [:clean-data :tags])
                 :cadence (get-in form [:clean-data :cadence])
                         :contacts (get-in form [:clean-data :contacts])}

        mparams {:method  (get-in form [:clean-data :method])
                 :headers (get-in form [:clean-data :headers])
                 :uri     (get-in form [:clean-data :uri])
                 :should-include (get-in form [:clean-data :should-include])}]
    (assoc (d/remove-nil-vals bparams)
           :params (d/remove-nil-vals mparams))))

(mf/defc monitor-test
  [{:keys [form] :as props}]
  (let [load? (mf/use-state false)
        res   (mf/use-state nil)
        check (fn []
                (reset! load? true)
                (let [data (prepare-submit-data @form)]
                  (->> (rp/req! :test-monitor data)
                       (rx/subs (fn [result]
                                  (swap! res result))
                                (fn [error]
                                  (reset! load? false)
                                  (when (:explain error)
                                    (js/console.log (:explain error))))
                                (fn []
                                  (reset! load? false))))))]

    [:div.monitor-test
     {:class (dom/classnames
              :deactivated (not (:valid @form))
              :success (= "up" (:status @res))
              :failed (= "down" (:status @res)))}
     (if @load?
       [:span.check-button.loader i/circle-notch]
       [:a.check-button
        {:title "Check"
         :on-click (when (and (true? (:valid @form))
                              (not @load?))
                     check)}
        i/check])

     [:div.result
      (cond
        (= "up" (:status @res))
        [:span "Success"]

        (= "down" (:status @res))
        [:span {:title (:reason @res)} "Failed"])]]))

(mf/defc monitor-form-base
  [{:keys [profile contacts] :as props}]
  (let [coptions       (get-cadence-options profile)
        form           (fm/use-form)
        available-tags (mf/use-state (get-in @form [:data :tags]))]

    (mf/use-effect
     (fn []
       (->> (rp/req! :retrieve-all-tags {})
            (rx/subs (fn [tags]
                       (swap! available-tags into tags))))))

    [:div.column
     [:div.form-row
      [:& fm/input
       {:name :name
        :type "text"
        :label "Label:"}]]

     [:div.form-row
      [:& fm/select
       {:options  (vec coptions)
        :value-fn (fn [id]
                    (d/seek #(= (str id) (:value %)) coptions))
        :label "Interval:"
        :name :cadence}]]

     [:div.form-row
      [:& fm/tags-select
       {:options @available-tags
        :label "Tags"
        :name :tags}]]

     [:div.form-row
      [:& fm/select
       {:label "Conctacts:"
        :options (map #(array-map :value (:id %)
                                  :label (:name %))
                      (vals contacts))

        :value-fn (fn [id]
                    (let [contact (get contacts id)]
                      #js {:value id :label (:name contact)}))

        :name :contacts
        :multiple true}]]]))

(s/def ::type #{"http"})
(s/def ::name ::us/not-empty-string)
(s/def ::cadence ::us/integer)
(s/def ::contacts (s/coll-of ::us/uuid :min-count 1))
(s/def ::method ::us/keyword)
(s/def ::uri ::us/uri)
(s/def ::should-include ::us/string)
(s/def ::headers ::headers-map)
(s/def ::tags (s/coll-of ::us/string :kind set?))

(s/def ::http-monitor-form
  (s/keys :req-un [::name ::type ::cadence ::contacts ::method ::uri]
          :opt-un [::id ::headers ::should-include ::tags]))

(mf/defc http-monitor-form
  [{:keys [item on-error] :as props}]
  (let [profile    (mf/deref st/profile-ref)
        contacts   (mf/deref st/contacts-ref)
        cancel-fn  (st/emitf (modal/hide))

        on-success #(modal/hide!)
        on-error   (fn [form err]
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

        on-submit
        (fn [form]
          (let [params (prepare-submit-data @form)
                params (with-meta params
                         {:on-success on-success
                          :on-error (partial on-error form)})]
            (if item
              (st/emit! (ptk/event :update-monitor params))
              (st/emit! (ptk/event :create-monitor params)))))

        params (:params item)

        initial
        (mf/use-memo
         (mf/deps item)
         (fn []
           (if item
             {:id       (:id item)
              :name     (:name item)
              :type     (:type item)
              :cadence  (:cadence item)
              :contacts (:contacts item #{})
              :method   (or (:method params) :get)
              :headers  (or (:headers params) {})
              :tags     (or (:tags item) #{})
              :uri      (:uri params)
              :should-include (or (:should-include params) "")}
             {:method :get
              :cadence 300
              :type "http"
              :contacts #{}})))

        form (fm/use-form :spec ::http-monitor-form
                          :initial initial)]

    [:div.modal.monitor-form-modal.form-container
     [:& fm/form {:on-submit on-submit :form form}
      [:div.modal-header
       [:div.modal-header-title
        (if item
          [:h2 "Update http/s monitor:"]
          [:h2 "Create http/s monitor"])]
       [:div.modal-close-button
        {:on-click cancel-fn} i/times]]

      [:div.modal-content.columns
       [:& monitor-form-base {:profile profile
                              :form form
                              :contacts contacts}]
       [:div.column
        [:div.form-row
         [:& fm/input
          {:type "text"
           :name :uri
           :label "URI:"}]]

        [:div.form-row
         [:& fm/select
          {:label "Request method:"
           :options [{:label "GET" :value :get}
                     {:label "HEAD" :value :head}]
           :value-fn (fn [val]
                       #js {:label (str/upper (name val)) :value val})

           :name :method}]]

        [:div.form-row
         [:& fm/input
          {:label "Should include text?"
           :type "text"
           :name :should-include}]]

        [:div.form-row
         [:& fm/input
          {:label "Raw headers:"
           :value-fn (fn [o]
                       (if (map? o)
                         (->> (reduce-kv #(conj %1 (str %2 ": " %3)) [] o)
                              (str/join "\n"))
                         o))
           :type "textarea"
           :name :headers}]]]]

      [:div.modal-footer
       [:div.action-buttons
        [:& fm/submit-button {:form form :label "Submit"}]
        [:& monitor-test {:form form}]]]]]))


(mf/defc monitor-form-modal
  {::mf/wrap-props false
   ::mf/register modal/components
   ::mf/register-as :monitor-form}
  [props]
  (let [on-close (st/emitf (modal/hide))]
    [:div.modal-overlay
     [:> http-monitor-form props]]))
