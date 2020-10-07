;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitor-list
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.events :as ev]
   [app.repo :as rp]
   [app.store :as st]
   [app.ui.confirm]
   [app.ui.forms :refer [tags-select]]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.ui.monitor-form]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.router :as r]
   [app.util.time :as tm]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(s/def ::tags (s/coll-of ::us/string :kind set?))
(s/def ::status ::us/not-empty-string)
(s/def ::filters-form
  (s/keys :opt-un [::tags ::status]))

(mf/defc header-filters
  [{:keys [filters] :as props}]
  (let [form  (fm/use-form :spec ::filters-form :initial {})
        atags (mf/use-state (or (get-in @form [:data :tags]) #{}))

        update-status-filter
        (mf/use-callback
         (fn [val]
           (swap! form update-in [:data :status]
                  (fn [local]
                    (cond
                      (= local val) "all"
                      (not= local val) val
                      :else local)))))]

    (mf/use-effect
     #(->> (rp/req! :retrieve-all-tags {})
           (rx/subs (fn [tags] (swap! atags into tags)))))

    (mf/use-effect
     (mf/deps @form)
     (st/emitf (ev/update-monitor-list-filters (:clean-data @form))))

    [:div.filters
     [:div.search
      [:& tags-select
       {:options @atags
        :form form
        :name :tags}]]
     [:div.status-filter
      [:span.label "FILTER:"]
      [:ul
       [:li {:class (dom/classnames :active (= "up" (get-in @form [:class :status])))
             :on-click (partial update-status-filter "up")}
        [:input {:type "checkbox"
                 :read-only true
                 :checked (= "up" (get-in @form [:clean-data :status]))}]
        [:label "Up"]]
       [:li {:class (dom/classnames :active (= "down" (get-in form [:class :status])))
             :on-click (partial update-status-filter "down")}
        [:input {:type "checkbox"
                 :read-only true
                 :checked (= "down" (get-in @form [:clean-data :status]))}]
        [:label "Down"]]]]]))

(mf/defc header
  [{:keys [filters] :as props}]
  (let [open-form (st/emitf (modal/show {:type :monitor-form}))]
    [:div.header
     [:& header-filters {:filters filters}]
     [:div.options
      [:a.add-monitor {:on-click open-form} i/plus]]]))

(mf/defc monitor-item
  [{:keys [item] :as props}]
  (let [status (:status item)
        router (mf/deref st/router-ref)
        uri    (str "#" (r/resolve router :monitor-detail {:id (:id item)}))

        delete-fn
        (mf/use-callback
         (mf/deps item)
         (st/emitf (ev/delete-monitor item)))

        on-delete
        (mf/use-callback
         (mf/deps item)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (st/emit! (modal/show
                      {:type :confirm
                       :title "Deleting monitor"
                       :on-accept delete-fn
                       :accept-label "Delete monitor"
                       :message "Do you want to delete this monitor?"}))))

        on-edit
        (mf/use-callback
         (mf/deps item)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (st/emit! (modal/show {:type :monitor-form :item item}))))

        on-hover
        (mf/use-callback
         (mf/deps item)
         (fn [event]
           (let [target (dom/get-target event)]
             (.setAttribute target "title" (tm/timeago (:modified-at item))))))]

    [:li {:key (:id item)
          :class (dom/classnames
                  :success (= "up" status)
                  :failed  (= "down" status)
                  :nodata  (nil? status))}
     [:a {:href uri}
      [:div.monitor-status
       {:title (case status
                 "up" "Everything is ok"
                 "down"  "The service seems down"
                 "Waiting data")}

       (case status
         "up" i/check-circle
         "down"  i/times-circle
         "nodata" i/circle
         i/circle)]

      [:div.monitor-title
       [:span (:name item)]]

      [:div.monitor-tags
       (if (seq (:tags item))
         [:span (apply str (interpose " " (:tags item)))]
         [:span "---"])]

      [:div.monitor-updated
       {:on-mouse-enter on-hover
        :title (tm/timeago (:modified-at item))}
       (if-let [ma (:monitored-at item)]
         (tm/format ma "PPpp")
         "---")]

      [:div.monitor-options
       [:div {:on-click on-edit} i/pen-square]
       [:div {:on-click on-delete} i/trash-alt]]]]))

(defn- apply-filters
  [filters monitors]
  (when monitors
    (cond->> (vals monitors)
      (not (empty? (:tags filters)))
      (filter (fn [item]
                (seq (set/intersection (:tags item) (:tags filters)))))

      (not= "all" (:status filters "all"))
      (filter #(= (:status filters) (:status %)))

      true (vec))))

(mf/defc monitor-list
  [{:keys [filters] :as props}]
  (let [monitors (->> (mf/deref st/monitors-ref)
                      (apply-filters filters)
                      (sort-by :created-at))]
    [:div.monitor-list
     (cond
       (nil? monitors)
       [:div.monitors-empty
        [:h3 "Loading..."]]

       (empty? monitors)
       [:div.monitors-empty
        [:h3 "No monitors found."]]

       (not (empty? monitors))
       [:*
        [:ul.monitor-list-header
         [:li
          [:div.monitor-status ""]
          [:div.monitor-title "Title"]
          [:div.monitor-tags "Tags"]
          [:div.monitor-updated "Monitored at"]
          [:div.monitor-options "Options"]
          ]]
        [:ul.monitor-list-body
         (for [item monitors]
           [:& monitor-item {:key (:id item) :item item}])]])]))

(def monitor-list-filters-ref
  (l/derived :monitor-list-filters st/state))

(mf/defc monitor-list-page
  {::mf/wrap [mf/memo]}
  []
  (mf/use-effect
   (fn []
     (st/emit! (ptk/event :initialize-monitor-list))
     (fn []
       (st/emit! (ptk/data-event :finalize-monitor-list)))))

  (let [filters (mf/deref monitor-list-filters-ref)]
    [:main.monitor-list-section
     [:section
      [:& header {:filters filters}]
      [:& monitor-list {:filters filters}]]]))

