;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns app.ui.monitors
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.events :as ev]
   [app.repo :as rp]
   [app.store :as st]
   [app.ui.confirm]
   [app.ui.dropdown :refer [dropdown]]
   [app.ui.forms :as fm]
   [app.ui.icons :as i]
   [app.ui.modal :as modal]
   [app.ui.monitors.http-form]
   [app.ui.monitors.ssl-form]
   [app.ui.monitors.http :refer [http-monitor-detail]]
   [app.util.dom :as dom]
   [app.util.router :as r]
   [app.util.time :as dt]
   [app.util.object :as obj]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [app.util.timers :as tm]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(s/def ::tags (s/coll-of ::us/string :kind set?))
(s/def ::status ::us/not-empty-string)
(s/def ::filters-form
  (s/keys :opt-un [::tags ::status]))

(mf/defc tags-input
  {::mf/wrap-props false
   ::mf/wrap [#(mf/deferred % tm/raf)]}
  [props]
  (let [options   (obj/get props "options")
        on-change (obj/get props "on-change")
        value-fn  #(js-obj "label" % "value" %)
        options   (into-array (map value-fn options))
        value     (obj/get props "value")
        value     (into-array (map value-fn value))

        on-change*
        (mf/use-callback
         (fn [item]
           (let [value (into #{} (map #(obj/get % "value")) (seq item))]
             (on-change value))))]

    [:div.form-field
     [:> fm/creatable-select
      {:options options
       :defaultInputValue ""
       :className "react-select"
       :classNamePrefix "react-select"
       :isMulti true
       :onChange on-change*
       :value value}]]))


(mf/defc header-filters
  [{:keys [filters] :as props}]
  (let [tags  (mf/use-state (:tags filters))

        update-status
        (mf/use-callback
         (mf/deps filters)
         (fn [val]
           (let [filters (update filters :status
                                 (fn [lval]
                                   (cond
                                     (= lval val) "all"
                                     (not= lval val) val
                                     :else lval)))]
             (st/emit! (r/nav' :monitor-list {} filters)))))

        update-tags
        (mf/use-callback
         (mf/deps filters)
         (fn [tags]
           (let [filters (assoc filters :tags tags)]
             (st/emit! (r/nav' :monitor-list {} filters)))))]

    (mf/use-effect
     #(->> (rp/req! :retrieve-all-tags {})
           (rx/subs (fn [v] (swap! tags into v)))))

    [:div.monitor-filters
     [:div.search
      [:& tags-input
       {:options @tags
        :value (:tags filters)
        :on-change update-tags}]]

     [:div.status-filter
      [:span.label "FILTER:"]
      [:ul
       [:li {:class (dom/classnames :active (= "up" (:status filters)))
             :on-click (partial update-status "up")}
        [:input {:type "checkbox"
                 :read-only true
                 :checked (= "up" (:status filters))}]
        [:label "Up"]]
       [:li {:class (dom/classnames :active (= "down" (:status filters)))
             :on-click (partial update-status "down")}
        [:input {:type "checkbox"
                 :read-only true
                 :checked (= "down" (:status filters))}]
        [:label "Down"]]]]]))

(mf/defc header
  [{:keys [filters] :as props}]
  (let [create-http-monitor
        (mf/use-callback
         (st/emitf (modal/show {:type :http-monitor-form})))

        create-ssl-monitor
        (mf/use-callback
         (st/emitf (modal/show {:type :ssl-monitor-form})))

        show-dropdown? (mf/use-state false)
        show-dropdown  (mf/use-callback #(reset! show-dropdown? true))
        hide-dropdown  (mf/use-callback #(reset! show-dropdown? false))]

    [:div.options-bar
     [:& header-filters {:filters filters}]
     [:a.add-button {:on-click show-dropdown} i/plus
      [:& dropdown {:show @show-dropdown?
                    :on-close hide-dropdown}
       [:ul.dropdown
        [:li {:on-click create-http-monitor
              :title "HTTP & HTTPS monitor."}
         [:div.icon i/cloud]
         [:div.text "HTTP"]]
        [:li {:on-click create-ssl-monitor
              :title "SSL certificate monitor."}
         [:div.icon i/shield-alt]
         [:div.text "SSL Cert"]]
        [:li.disabled {:title "DNS registry monitor (not implemented yet)."}
         [:div.icon i/globe]
         [:div.text "DNS"]]
        [:li.disabled {:title "Heartbeat / Keepalive monitor (not implemented yet)."}
         [:div.icon i/heartbeat]
         [:div.text "Keepalive"]]]]]]))


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
           (st/emit! (modal/show {:type :http-monitor-form :item item}))))

        on-hover
        (mf/use-callback
         (mf/deps item)
         (fn [event]
           (let [target (dom/get-target event)]
             (.setAttribute target "title" (dt/timeago (:modified-at item))))))]

    [:li.row {:key (:id item)
              :class (dom/classnames
                      :inactive (= "paused" status)
                      :inactive (= "started" status)
                      :success (= "up" status)
                      :failed  (= "down" status)
                      :nodata  (nil? status))}
     [:a {:href uri}
      [:div.monitor-status
       {:title (str (str/upper (:type item)) ": "
                    (case status
                      "up" "Everything is ok"
                      "down"  "The service seems down"
                      "Waiting data"))}

       (case (:type item)
         "http" i/cloud
         "ssl"  i/shield-alt
         nil)]

      [:div.monitor-title
       [:span (:name item)]]

      [:div.monitor-tags
       (if (seq (:tags item))
         [:span (apply str (interpose " " (:tags item)))]
         [:span "---"])]

      [:div.monitor-updated
       {:on-mouse-enter on-hover
        :title (dt/timeago (:modified-at item))}
       (if-let [ma (:monitored-at item)]
         (dt/format ma :datetime-med)
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
       [:div.table
        [:div.table-header
         [:div.row
           [:div.monitor-status ""]
          [:div.monitor-title "Title"]
          [:div.monitor-tags "Tags"]
          [:div.monitor-updated "Monitored at"]
          [:div.monitor-options "Options"]
          ]]
        [:div.table-body
         (for [item monitors]
           [:& monitor-item {:key (:id item) :item item}])]])]))

(def monitor-list-filters-ref
  (l/derived :monitor-list-filters st/state))

(mf/defc monitor-list-page
  {::mf/wrap [mf/memo]}
  [{:keys [params] :as props}]
  (mf/use-effect
   (fn []
     (st/emit! (ptk/event :initialize-monitor-list))
     (st/emitf (ptk/event :finalize-monitor-list))))

  [:main.monitor-list-section
   [:div.single-column-1200
    [:& header {:filters params}]
    [:& monitor-list {:filters params}]]])

(defn monitor-ref
  [id]
  (l/derived (l/in [:monitors id]) st/state))

(mf/defc monitor-detail-page
  {::mf/wrap [mf/memo]}
  [{:keys [id section] :as props}]
  (let [monitor-ref (mf/use-memo (mf/deps id) #(monitor-ref id))
        monitor     (mf/deref monitor-ref)]

    (mf/use-effect
     (fn []
       (st/emit! (ptk/event :initialize-monitor-detail {:id id}))
       (st/emitf (ptk/event :finalize-monitor-detail {:id id}))))

    (when monitor
      (case (:type monitor)
        "http" [:& http-monitor-detail {:monitor monitor}]
        "ssl"  [:div "TODO"]
        nil))))


