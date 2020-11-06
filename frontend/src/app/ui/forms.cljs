;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.forms
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.util.object :as obj]
   [app.util.timers :as tm]
   [app.util.forms :as fm]
   [app.util.object :as obj]
   [app.util.i18n :as i18n :refer [t]]
   [app.util.dom :as dom]
   ["react-select" :default Select]
   ["react-select/creatable" :default CreatableSelect]))


(def form-ctx (mf/create-context nil))

(defn use-form
  ([] (mf/use-ctx form-ctx))
  ([& args] (apply fm/use-form args)))

(def rselect Select)

(mf/defc input
  [{:keys [type label disabled name form hint children trim value-fn] :as props}]
  (let [form       (or form (mf/use-ctx form-ctx))

        value-fn   (or value-fn identity)
        type'      (mf/use-state type)
        focus?     (mf/use-state false)
        locale     (mf/deref i18n/locale)

        touched?   (get-in @form [:touched name])
        error      (get-in @form [:errors name])

        value      (or (get-in @form [:data name]) "")
        klass (dom/classnames
               :focus     @focus?
               :valid     (and touched? (not error))
               :invalid   (and touched? error)
               :disabled  disabled
               :empty     (str/empty? value))

        on-focus  #(reset! focus? true)
        on-change (fm/on-input-change form name trim)

        on-blur
        (fn [event]
          (reset! focus? false)
          (when-not (get-in @form [:touched name])
            (swap! form assoc-in [:touched name] true)))

        props (-> props
                  (dissoc :help-icon :form :children :trim :value-fn)
                  (assoc :value (value-fn value)
                         :on-focus on-focus
                         :on-blur on-blur
                         :on-change on-change
                         :type @type')
                  (obj/clj->props))]

    [:div.form-field
     {:class klass}
     [:*
      [:label label]
      (if (= "textarea" type)
        [:> :textarea props]
        [:> :input props])

      (cond
        (and touched? (:message error))
        [:span.error (t locale (:message error))]

        (string? hint)
        [:span.hint hint])
      children]]))

(mf/defc submit-button
  [{:keys [label form] :as props}]
  (let [form (or form (mf/use-ctx form-ctx))]
    [:input
     {:name "submit"
      :disabled (not (:valid @form))
      :value label
      :type "submit"}]))

(mf/defc form
  [{:keys [form on-submit children class ] :as props}]
  [:& (mf/provider form-ctx) {:value form}
   [:form {:class class
           :on-submit (fn [event]
                        (dom/prevent-default event)
                        (on-submit form event))}
    children]])

(mf/defc tags-select
  {::mf/wrap-props false
   ::mf/wrap [#(mf/deferred % tm/raf)]}
  [props]
  (let [form      (or (obj/get props "form")
                      (mf/use-ctx form-ctx))
        label     (obj/get props "label")
        name      (obj/get props "name")
        options   (obj/get props "options")

        value-fn  #(js-obj "label" % "value" %)
        options   (into-array (map value-fn options))

        touched?  (get-in @form [:touched name])
        error     (get-in @form [:errors name])

        value     (or (get-in @form [:data name]) [])
        value     (into-array (map value-fn value))

        on-blur
        (mf/use-callback
         (mf/deps form)
         (fn [event]
           (when-not (get-in @form [:touched name])
             (swap! form assoc-in [:touched name] true))))

        on-change
        (mf/use-callback
         (mf/deps form)
         (fn [item]
           (let [value (into #{} (map #(obj/get % "value")) (seq item))]
             (swap! form (fn [state]
                           (-> state
                               (assoc-in [:data name] value)
                               (update :errors dissoc name)))))))

        props (-> (obj/without props [:from :children :label])
                  (obj/merge #js {:options options
                                  :defaultInputValue ""
                                  :className "react-select"
                                  :classNamePrefix "react-select"
                                  :isMulti true
                                  :onBlur on-blur
                                  :onChange on-change
                                  :value value}))]
    [:div.form-field
     (when label [:label label])
     [:> CreatableSelect props]
     (when (and touched? (:message error))
       [:span.error (:message error)])]))

(mf/defc select
  {::mf/wrap-props false
   ::mf/wrap [#(mf/deferred % tm/raf)]}
  [props]
  (let [form      (or (obj/get props "form")
                      (mf/use-ctx form-ctx))

        default   (obj/get props "default")
        value-fn  (obj/get props "value-fn")
        label     (obj/get props "label")
        options   (obj/get props "options")
        name      (obj/get props "name")
        mult      (or (obj/get props "multiple") false)

        focus?    (mf/use-state false)
        on-focus  #(reset! focus? true)

        touched?  (get-in @form [:touched name])
        error     (get-in @form [:errors name])

        value     (or (get-in @form [:data name]) default)
        value-fn  (comp clj->js value-fn)


        on-blur
        (fn [event]
          (reset! focus? false)
          (when-not (get-in @form [:touched name])
            (swap! form assoc-in [:touched name] true)))

        read-if-edn
        (mf/use-callback
         (fn [v]
           (if (and (string? v)
                    (str/starts-with? v "edn:"))
             (d/read-string (subs v 4))
             v)))

        on-change
        (fn [item]
          (let [value (if (array? item)
                        (into #{} (comp (map #(obj/get % "value"))
                                        (map read-if-edn)) item)
                        (read-if-edn (obj/get item "value")))]
            (swap! form (fn [state]
                          (-> state
                              (assoc-in [:data name] value)
                              (update :errors dissoc name))))))

        value (cond
                (set? value)
                (into-array (map value-fn value))

                (not (nil? value))
                (value-fn value)

                :else "")

        options (into-array
                 (for [item options]
                   #js {:label (:label item)
                        :value (str "edn:" (pr-str (:value item)))}))

        props (-> (obj/without props [:form :children :label :value-fn :options])
                  (obj/merge! #js {:isMulti mult
                                   :classNamePrefix "react-select"
                                   :className "react-select"
                                   :defaultInputValue ""
                                   :onBlur on-blur
                                   :inFocus on-focus
                                   :onChange on-change
                                   :options options
                                   :value value}))
        ]
    [:div.form-field
     [:label label]
     [:> Select props]
     (when (and touched? (:message error))
       [:span.error (:message error)])]))
