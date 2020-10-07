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
   [app.util.object :as obj]
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

(def select-styles
  {:container (fn [provided state]
                 (-> provided
                     ;; (obj/set! "display" "flex")
                     ;; (obj/set! "flexGrow" "1")
                     (obj/set! "width" "100%")))

   :control (fn [provided state]
              (-> provided
                  (obj/set! "padding" "2px")
                  (obj/set! "border" "1px solid #b1b2b5")
                  (obj/set! "borderRadius" "2px")))})

(defn- select-on-blur
  [form event]
  (when-not (get-in @form [:touched name])
    (swap! form assoc-in [:touched name] true)))

(defn- select-on-change
  [form name item]
  (let [value (obj/get item "value")]
    (swap! form (fn [state]
                  (-> state
                      (assoc-in [:data name] value)
                      (update :errors dissoc name))))))

(defn- select-on-change-multiple
  [form name item]
  (let [item  (or item #js [])
        value (into #{} (amap item i ret (obj/get (aget item i) "value")))]
    (swap! form (fn [state]
                  (-> state
                      (assoc-in [:data name] value)
                      (update :errors dissoc name))))))

(mf/defc select
  [{:keys [options label default name value-fn form]
    :or {value-fn identity}
    :as props}]
  (let [form      (or form (mf/use-ctx form-ctx))
        touched?  (get-in @form [:touched name])
        error     (get-in @form [:errors name])

        value     (or (get-in @form [:data name]) default)
        value     (clj->js (value-fn value))
        on-blur   (partial select-on-blur form name)
        on-change (partial select-on-change form name)

        props     (-> props
                      (dissoc :form :children :label :value-fn :default)
                      (assoc :styles select-styles)
                      (assoc :onBlur on-blur)
                      (assoc :onChange on-change)
                      (assoc :value value)
                      (obj/clj->props))]

    [:div.form-field
     [:label label]
     [:> Select props]
     (when (and touched? (:message error))
       [:span.error (:message error)])]))

(mf/defc select-multiple
  [{:keys [options label default name value-fn form]
    :or {value-fn identity}
    :as props}]
  (let [form      (or form (mf/use-ctx form-ctx))
        touched?  (get-in @form [:touched name])
        error     (get-in @form [:errors name])
        value     (or (get-in @form [:data name]) default)
        value     (clj->js (map value-fn value))
        on-blur   (partial select-on-blur form name)
        on-change (partial select-on-change-multiple form name)

        props     (-> props
                      (dissoc :form :children :label :value-fn)
                      (assoc :styles select-styles)
                      (assoc :isMulti true)
                      (assoc :onBlur on-blur)
                      (assoc :onChange on-change)
                      (assoc :value value)
                      (obj/clj->props))]

    [:div.form-field
     [:label label]
     [:> Select props]
     (when (and touched? (:message error))
       [:span.error (:message error)])]))

(mf/defc tags-select
  [{:keys [options label name form] :as props}]
  (let [form      (or form (mf/use-ctx form-ctx))
        value-fn  #(array-map :label % :value %)
        options   (clj->js (map value-fn options))

        touched?  (get-in @form [:touched name])
        error     (get-in @form [:errors name])
        value     (or (get-in @form [:data name]) [])
        value     (clj->js (map value-fn value))


        on-blur    (partial select-on-blur form name)
        on-change  (partial select-on-change-multiple form name)

        props (-> props
                  (dissoc :form :children :label)
                  (assoc :options options)
                  (assoc :styles select-styles)
                  (assoc :isMulti true)
                  (assoc :onBlur on-blur)
                  (assoc :onChange on-change)
                  (assoc :value value)
                  (obj/clj->props))]

    [:div.form-field
     (when label
       [:label label])
     [:> CreatableSelect props]
     (when (and touched? (:message error))
       [:span.error (:message error)])]))

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

(mf/defc select2
  [{:keys [options label default name multiple value-fn form]
    :or {value-fn identity
         multiple false}
    :as props}]
  (let [form      (or form (mf/use-ctx form-ctx))
        focus?    (mf/use-state false)
        on-focus  #(reset! focus? true)

        touched?   (get-in @form [:touched name])
        error      (get-in @form [:errors name])

        value     (or (get-in @form [:data name]) default)

        on-blur
        (fn [event]
          (reset! focus? false)
          (when-not (get-in @form [:touched name])
            (swap! form assoc-in [:touched name] true)))

        on-change
        (fn [item]
          (let [value (if (array? item)
                        (into [] (amap item i ret (obj/get (aget item i) "value")))
                        (obj/get item "value"))]
            (swap! form (fn [state]
                          (-> state
                              (assoc-in [:data name] value)
                              (update :errors dissoc name))))))

        props (-> props
                  (dissoc :form :children :label :multiple :value-fn)
                  (assoc :styles select-styles)
                  (assoc :isMulti multiple)
                  (assoc :onBlur on-blur)
                  (assoc :inFocus on-focus)
                  (assoc :onChange on-change)
                  (assoc :value
                         (cond
                           (vector? value)
                           (clj->js (map value-fn value))

                           (not (nil? value))
                           (clj->js (value-fn value))))
                  (obj/clj->props))]


    [:div.form-field
     [:label label]
     [:> Select props]
     (when (and touched? (:message error))
       [:span.error (:message error)])]))
