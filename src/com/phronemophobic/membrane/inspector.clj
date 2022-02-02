(ns com.phronemophobic.membrane.inspector
  (:require
   [membrane.ui :as ui]
   [membrane.basic-components :as basic]
   [membrane.toolkit :as tk]
   [membrane.component :refer [defui defeffect]
    :as component]
   [clojure.zip :as z]))

(defprotocol PWrapped
  (-unwrap [this]))

(deftype APWrapped [obj]
  Object
  (hashCode [_] (System/identityHashCode obj))
  PWrapped
  (-unwrap [_]
    obj))

(defn wrap [o]
  (->APWrapped o))


(def colors
  {:keyword [0.46666666865348816 0.0 0.5333333611488342],
   :number
   [0.06666667014360428 0.4000000059604645 0.2666666805744171],
   :def [0.0 0.0 1.0],
   :positive
   [0.13333334028720856 0.6000000238418579 0.13333334028720856],
   :bracket
   [0.6000000238418579 0.6000000238418579 0.46666666865348816],
   :comment [0.6666666865348816 0.3333333432674408 0.0],
   :attribute [0.0 0.0 0.800000011920929],
   :type [0.0 0.5333333611488342 0.3333333432674408],
   :quote [0.0 0.6000000238418579 0.0],
   :header [0.0 0.0 1.0],
   :atom
   [0.13333334028720856 0.06666667014360428 0.6000000238418579],
   :builtin [0.20000000298023224 0.0 0.6666666865348816],
   :hr [0.6000000238418579 0.6000000238418579 0.6000000238418579],
   :string-2 [1.0 0.3333333432674408 0.0],
   :string
   [0.6666666865348816 0.06666667014360428 0.06666667014360428],
   :meta [0.3333333432674408 0.3333333432674408 0.3333333432674408],
   :tag [0.06666667014360428 0.46666666865348816 0.0],
   :qualifier
   [0.3333333432674408 0.3333333432674408 0.3333333432674408],
   :variable-2 [0.0 0.3333333432674408 0.6666666865348816]})

(defonce toolkit
  (if-let [tk (resolve 'membrane.skia/toolkit)]
    @tk
    @(requiring-resolve 'membrane.java2d/toolkit)))

(def monospaced
  (if (tk/font-exists? toolkit (ui/font "Menlo" 11))
    (ui/font "Menlo" 11)
    (ui/font "monospaced" 11)))
(def cell-width
  (tk/font-advance-x toolkit monospaced " "))
(def cell-height
  (tk/font-line-height toolkit monospaced))

(defn indent [n]
  (ui/spacer (* n cell-width) 0))

(defn inspector-dispatch [{:keys [obj width height]}]
  (if (or (<= width 0)
          (<= height 0))
    :no-space
    (cond
      (string? obj) :string
      (integer? obj) :integer
      (float? obj) :float
      (double? obj) :double
      (ratio? obj) :ratio
      (char? obj) :char
      (map-entry? obj) :map-entry
      (map? obj) :map
      (set? obj) :set
      (list? obj) :list
      (vector? obj) :vector
      (symbol? obj) :symbol
      (keyword? obj) :keyword
      (boolean? obj) :boolean
      (nil? obj) :nil

      (coll? obj) :collection
      (seqable? obj) :seqable
      (instance? clojure.lang.IDeref obj) :deref
      (instance? APWrapped obj) :pwrapped
      (fn? obj) :fn
      :else :object)))


(defmulti inspector* inspector-dispatch)

(defn ilabel [o width]
  (let [s (str o)
        len (count s)
        shortened
        (when (pos? len)
          (if (<= len width)
            s
            (case width
              0 nil
              (1 2 3) (subs s 0 (min len width))

              ;; else
              (str (subs s 0 (max 0
                                  (- width 3)))
                   "..."))))]
    (when shortened
      (ui/label shortened monospaced))))

(defn split-evenly [width n]
  (if (zero? n)
    []
    (let [chunk-size (max 1
                          (int (/ width n)))]
      (vec
       (reverse
        (loop [partitions []
               width width]
          (cond
            (zero? width) partitions

            (>= (inc (count partitions))
                n)
            (conj partitions width)

            :else (recur (conj partitions chunk-size)
                         (- width chunk-size)))))))))

(defn split-ratio [width r]
  (let [left (int (Math/ceil (* r width)))
        right (- width left)]
    [left right]))

(defmethod inspector* :default
  [{:keys [obj width height]}]
  (let [[left right] (split-ratio width 1/3)]
    (ui/horizontal-layout
     (ilabel (inspector-dispatch {:obj obj
                                  :width width
                                  :height height})
             left)
     (ilabel (type obj)
             right))))

(defmethod inspector* :no-space
  [{:keys [obj width height]}]
  nil)

(defmethod inspector* :string
  [{:keys [obj width height]}]
  (ui/with-color (:string colors)
    (ilabel (pr-str obj)
            width)))

(defn wrap-selection [x path elem]
  (ui/wrap-on
   :mouse-down
   (fn [handler pos]
     (let [intents (handler pos)]
       (if (seq intents)
         intents
         [[::select x path]])))
   elem))

(defn wrap-highlight [path highlight-path elem]
  (let [body
        (ui/wrap-on
         :mouse-move
         (fn [handler pos]
           (let [intents (handler pos)]
             (if (seq intents)
               intents
               [[::highlight path]])))
         elem)]
   (if (= path highlight-path)
     (ui/fill-bordered [0.2 0.2 0.2 0.1]
                       0
                       body)
     body)))

(defn inspector-seq-horizontal [{:keys [obj
                                        width
                                        height
                                        highlight-path
                                        path
                                        offset
                                        open close]}]
  (let [open-close-width (+ (count open)
                            (count close))]
    (when (> width open-close-width )
      (let [body
            (loop [body []
                   i 0
                   width (- width
                            (count open)
                            (count close))
                   ;; lazy sequences can throw here
                   obj (try
                         (seq obj)
                         (catch Exception e
                           e))]
              (if (instance? Exception obj)
                (conj body
                      (inspector* {:obj obj
                                   :height height
                                   :width width
                                   :highlight-path highlight-path
                                   :path path}))
                (if (or (not obj)
                        (<= width 0))
                  body
                  (let [x (first obj)
                        child-path (if (map-entry? x)
                                     (list 'find (key x))
                                     (list 'nth i))
                        path (conj path
                                   child-path)
                        elem
                        (wrap-highlight
                         path
                         highlight-path
                         (wrap-selection
                          x
                          path
                          (inspector* {:obj x
                                       :height 1
                                       :highlight-path highlight-path
                                       :path path
                                       :width width})))
                        pix-width (ui/width elem)
                        elem-width (int (Math/ceil (/ pix-width
                                                      cell-width)))]
                    (recur (conj body elem)
                           (inc i)
                           (- width elem-width
                              ;; add a space between elements
                              1
                              )
                           ;; lazy sequences can throw here
                           (try
                             (next obj)
                             (catch Exception e
                               e)))))))]
        (when (pos? (count body))
          (ui/horizontal-layout
           (ui/with-color (:bracket colors)
             (ilabel open (count open)))
           (apply ui/horizontal-layout
                  (interpose (indent 1)
                             body))
           (let [len (try
                       (bounded-count (inc (count body)) obj)
                       (catch Exception e
                         nil))]
             (when (= (count body) len)
              (ui/with-color (:bracket colors)
                (ilabel close (count close))))))))))
  )

(def chunk-size 32)
(defn inspector-seq [{:keys [obj
                             width
                             height
                             highlight-path
                             path
                             offset
                             open close]
                      :as m}]
  (let [offset (or offset 0)]
    (if (<= height 3)
      (inspector-seq-horizontal m)
      (let [;; realize elements
            ;; lazy sequences can throw errors here
            chunk (try
                    (loop [obj (seq (if (pos? offset)
                                      (drop offset obj)
                                      obj))
                           chunk []]
                      (if (or (not obj)
                              (>= (count chunk)
                                  chunk-size))
                        chunk
                        (let [x (first obj)]
                          (let [next-obj
                                ;; lazy sequences can throw here
                                (try
                                  (next obj)
                                  (catch Exception e
                                    e))]
                            (if (instance? Exception next-obj)
                              (conj chunk next-obj)
                              (recur next-obj
                                     (conj chunk x)))))))
                    (catch Exception e
                      e))

            children
            (if (instance? Exception chunk)
              (inspector* {:obj chunk
                           :height height
                           :width width
                           :highlight-path highlight-path
                           :path path})
              (let [heights (split-evenly (- height 3)
                                          (count chunk))]
                (->> chunk
                     (map (fn [i height obj]
                            (let [child-path (if (map-entry? obj)
                                               (list 'find (key obj))
                                               (list 'nth i))
                                  path (conj path
                                             child-path)
                                  body
                                  (wrap-highlight
                                   path
                                   highlight-path
                                   (wrap-selection obj
                                                   path
                                                   (inspector* {:obj obj
                                                                :height height
                                                                :path path
                                                                :highlight-path highlight-path
                                                                :width (dec width)})))]
                              (if (= path highlight-path)
                                (ui/fill-bordered [0.2 0.2 0.2 0.1]
                                                  0
                                                  body)
                                body)))
                          (range)
                          heights)
                     (apply ui/vertical-layout))))]
        (cond
          (instance? Exception chunk)
          children

          (empty? chunk)
          (ui/with-color (:bracket colors)
            (ilabel (str open close) width))

          ;; else not empty
          :else
          (ui/vertical-layout
           (ui/with-color (:bracket colors)
             (ilabel open width))
           (ui/translate cell-width 0
                         (ui/vertical-layout
                          (when (pos? offset)
                            (ui/on
                             :mouse-down
                             (fn [_]
                               ;; only for top level
                               (when (empty? path)
                                 [[::previous-chunk]]))
                             (ilabel "..." 3)))
                          children
                          ;; lazy sequences can throw here
                          (let [len (try
                                      (bounded-count (inc chunk-size)
                                                     (drop offset obj))
                                      (catch Exception e
                                        (println e)
                                        nil))]
                            (when (and len
                                       (> len (count children)))
                              (ui/on
                               :mouse-down
                               (fn [_]
                                 ;; only for top level
                                 (when (empty? path)
                                   [[::next-chunk (count children)]]))
                               (ilabel "..." 3))))
                          ))
           (ui/with-color (:bracket colors)
             (ilabel close width))))))))


(defmethod inspector* :vector
  [{:keys [obj width height] :as m}]
  (inspector-seq (assoc m
                        :open "["
                        :close "]")))

(defmethod inspector* :seqable
  [{:keys [obj width height] :as m}]
  (inspector-seq (assoc m
                        :open "("
                        :close ")")))

(defmethod inspector* :collection
  [{:keys [obj width height] :as m}]
  (inspector-seq (assoc m
                        :open "("
                        :close ")")))

(defmethod inspector* :list
  [{:keys [obj width height] :as m}]
  (inspector-seq (assoc m
                        :open "("
                        :close ")")))

(defmethod inspector* :set
  [{:keys [obj width height] :as m}]
  (inspector-seq (assoc m
                        :open "#{"
                        :close "}")))



(defmethod inspector* :map
  [{:keys [obj width height] :as m}]
  (inspector-seq (assoc m
                        :open "{"
                        :close "}"))
  )

(defn inspector-keyword [{:keys [obj width height]}]
  (let [ns (namespace obj)
        [left right] (if ns
                       (split-ratio (- width 2) 1/3)
                       [0 (- width 1)])]
    (ui/with-color (:keyword colors)
      (ui/horizontal-layout
       (ilabel ":" 1)
       (when ns
         (ui/horizontal-layout
          (ilabel ns left)
          (ilabel "/" 1)))
       (ilabel (name obj) right)))))
(defmethod inspector* :keyword
  [{:keys [obj width height] :as m}]
  (inspector-keyword m))


(defn inspector-map-entry [{:keys [obj width height path highlight-path]}]
  (let [[left right] (split-ratio (- width 2) 1/3)
        [k v] obj]
    (ui/horizontal-layout
     (let [child-path (conj path '(key))]
      (wrap-highlight
       child-path
       highlight-path
       (wrap-selection k
                       child-path
                       (inspector* {:obj k
                                    :height height
                                    :path child-path
                                    :highlight-path highlight-path
                                    :width left}))))
     (indent 1)
     (let [child-path (conj path '(val))]
      (wrap-highlight
       child-path
       highlight-path
       (wrap-selection v
                       child-path
                       (inspector* {:obj v
                                    :height height
                                    :path child-path
                                    :highlight-path highlight-path
                                    :width right})))))))
(defmethod inspector* :map-entry
  [{:keys [obj width height] :as m}]
  (inspector-map-entry m))

(defn inspector-deref [{:keys [obj width height path highlight-path]}]
  (let [[left right] (split-ratio (- width 2) 1/3)
        k (symbol (.getName (class obj)))
        v (if (instance? clojure.lang.IPending obj)
            (if (realized? obj)
              @obj
              "unrealized?")
            (deref obj))]
    (ui/horizontal-layout
     (indent 1)
     (inspector* {:obj k
                  :height height
                  :width left})
     (indent 1)
     (let [child-path (conj path '(deref))]
       (wrap-highlight
        child-path
        highlight-path
        (wrap-selection v
                        child-path
                        (inspector* {:obj v
                                     :height height
                                     :path child-path
                                     :highlight-path highlight-path
                                     :width right})))))))
(defmethod inspector* :deref
  [{:keys [obj width height] :as m}]
  (inspector-deref m))

(defn inspector-pwrapped [{:keys [obj width height path highlight-path]}]
  (let [[left right] (split-ratio (- width 2) 1/3)
        k 'PWrapped
        v (-unwrap obj)]
    (ui/horizontal-layout
     (indent 1)
     (inspector* {:obj k
                  :height height
                  :width left})
     (indent 1)
     (let [child-path (conj path '(-unwrap))]
       (wrap-highlight
        child-path
        highlight-path
        (wrap-selection v
                        child-path
                        (inspector* {:obj v
                                     :height height
                                     :path child-path
                                     :highlight-path highlight-path
                                     :width right})))))))

(defmethod inspector* :pwrapped
  [{:keys [obj width height] :as m}]
  (inspector-pwrapped m))

(defn inspector-fn [{:keys [obj width height]}]
  (ilabel "#function" width))

(defmethod inspector* :fn
  [{:keys [obj width height] :as m}]
  (inspector-fn m))


(defn inspector-symbol [{:keys [obj width height]}]
  (let [ns (namespace obj)
        [left right] (if ns
                       (split-ratio (- width 1) 1/3)
                       [0 width])]
    (ui/with-color (:qualifier colors)
      (ui/horizontal-layout
       (when ns
         (ui/horizontal-layout
          (ilabel ns left)
          (ilabel "/" 1)))
       (ilabel (name obj) right)))))
(defmethod inspector* :symbol
  [{:keys [obj width height] :as m}]
  (inspector-symbol m))


(defn inspector-integer [{:keys [obj width height]}]
  (ui/with-color (:number colors)
    (ilabel obj width)))
(defmethod inspector* :integer
  [{:keys [obj width height] :as m}]
  (inspector-integer m))

(defn inspector-float [{:keys [obj width height]}]
  (ui/with-color (:number colors)
    (ilabel obj width)))
(defmethod inspector* :float
  [{:keys [obj width height] :as m}]
  (inspector-float m))

(defn inspector-double [{:keys [obj width height]}]
  (ui/with-color (:number colors)
    (ilabel obj width)))
(defmethod inspector* :double
  [{:keys [obj width height] :as m}]
  (inspector-double m))

(defn inspector-ratio [{:keys [obj width height]}]
  (ui/with-color (:number colors)
    (ilabel obj width)))
(defmethod inspector* :ratio
  [{:keys [obj width height] :as m}]
  (inspector-ratio m))


(defn inspector-char [{:keys [obj width height]}]
  (ui/with-color (:string colors)
    (ui/horizontal-layout
     (ilabel "\\" 1)
     (ilabel obj (dec width)))))
(defmethod inspector* :char
  [{:keys [obj width height] :as m}]
  (inspector-char m))

(defn inspector-boolean [{:keys [obj width height]}]
  (ui/with-color (:number colors)
    (ui/horizontal-layout
     (ilabel obj width))))
(defmethod inspector* :boolean
  [{:keys [obj width height] :as m}]
  (inspector-boolean m))

(defn inspector-nil [{:keys [obj width height]}]
  (ilabel "nil" width))
(defmethod inspector* :nil
  [{:keys [obj width height] :as m}]
  (inspector-nil m))


(defui wrap-resizing [{:keys [resizing?
                              width
                              height
                              body]}]
  (if-not resizing?
    body
    (let [w (+  (* width cell-width))
          h (+  (* height cell-height))
          temp-width (get extra ::temp-width w)
          temp-height (get extra ::temp-height h)]
     (ui/on
      :mouse-up
      (fn [_]
        [[:set $resizing? false]])
      :mouse-move-global
      (fn [[x y]]
        [[:set $width (int (/ x  cell-width))]
         [:set $height (int (/ y  cell-height))]
         [:set $temp-width x]
         [:set $temp-height y]])
      (ui/no-events
       [body
        (ui/with-color [0.2 0.2 0.2 0.2]
          (ui/with-style :membrane.ui/style-stroke
            (ui/rectangle w h)))
        (ui/spacer (+ temp-width 5)
                   (+ temp-height 5))])))))

(defui inspector [{:keys [obj width height show-context?]}]
  (let [stack (get extra :stack [])
        path (get extra :path [])
        offsets (get extra :offsets [0])
        offset (peek offsets)
        specimen (get extra :specimen obj)
        resizing? (get extra :resizing?)
        highlight-path (get extra :highlight-path)]
    (ui/vertical-layout
     (when show-context?
         (ui/vertical-layout
          (basic/button {:text "pop"
                         :on-click
                         (fn []
                           (when (seq stack)
                             (let [{:keys [specimen path offsets]} (peek stack)]
                               [[:set $specimen specimen]
                                [:set $path path]
                                [:set $offsets offsets]
                                [:update $stack pop]])))})
          (basic/button {:text "resizing"
                         :on-click
                         (fn []
                           [[:set $resizing? true]])})
          (ui/label (str "offset: " offset))
          (ui/label (str "path: " (pr-str path) ))))
     (wrap-resizing
      {:resizing? resizing?
       :width width
       :height height
       :body
       (let [elem
             (ui/on
              ::highlight
              (fn [path]
                [[:set $highlight-path path]])
              ::previous-chunk
              (fn []
                [[:update $offsets
                  (fn [offsets]
                    (if (> (count offsets) 1)
                      (pop offsets)
                      offsets))]])
              ::next-chunk
              (fn [delta]
                [[:update $offsets
                  (fn [offsets]
                    (let [offset (peek offsets)]
                      (conj offsets (+ offset delta))))]])

              ::select
              (fn [x child-path]
                [[:update $stack conj {:specimen specimen
                                       :path path
                                       :offsets offsets}]
                 [:delete $highlight-path]
                 [:update $path into child-path]
                 [:set $offsets [0]]
                 [:set $specimen (wrap x)]])
              (ui/wrap-on
               :mouse-move
               (fn [handler pos]
                 (let [intents (handler pos)]
                   (if (seq intents)
                     intents
                     [[:set $highlight-path nil]])))
               (inspector* {:obj (-unwrap specimen)
                            :height height
                            :path []
                            :offset offset
                            :highlight-path highlight-path
                            :width width} )))
             [ew eh] (ui/bounds elem)
             pop-button
             (ui/on
              :mouse-down
              (fn [_]
                (if (seq stack)
                  (let [{:keys [specimen path offsets]} (peek stack)]
                    [[:set $specimen specimen]
                     [:set $path path]
                     [:set $offsets offsets]
                     [:update $stack pop]])
                  [[:delete $specimen]
                   [:delete $path]
                   [:delete $offsets]
                   [:delete $stack]]))
              (ui/filled-rectangle [0 0 1 0.25]
                                   8 8))
             resize-button
             (ui/on
              :mouse-down
              (fn [_]
                [[:set $resizing? true]])
              (ui/filled-rectangle [1 0 0 0.25]
                                   8 8))
             [rw rh] (ui/bounds resize-button)]
         [(ui/translate (- (* width cell-width)
                           rw)
                        (- (* height cell-height)
                           rh)
                        resize-button)
          (ui/translate (- (* width cell-width)
                           (* 2 rw))
                        (- (* height cell-height)
                           rh)
                        pop-button)
          elem]
         )}))))





(defn inspect
  ([obj]
   (inspect obj {}))
  ([obj {:keys [width height show-context?] :as opts
         :or {show-context? true}}]
   (let [width (or width 80)
         height (or height 40)
         app (component/make-app #'inspector
                                 {:obj (wrap obj)
                                  :width width
                                  :show-context? show-context?
                                  :height height})

         [empty-width empty-height] (ui/bounds ((component/make-app #'inspector
                                                                    {:obj (wrap nil)
                                                                     :width 0
                                                                     :height 0})))
         window-width (max empty-width
                           (* cell-width width))
         window-height (+ empty-height
                          height
                          (* cell-height (inc height)))]
     (tk/run
       toolkit
       app
       {:window-title "Inspect"
        :window-start-width window-width
        :window-start-height window-height}))))



(comment
  [clojure.spec.alpha :as s]
  [clojure.spec.gen.alpha :as gen]
  (s/def ::anything any? )
  
  (do
    (def obj (gen/generate (s/gen ::anything) )
      )
    (inspect (gen/sample  (s/gen ::anything)
                          100))
    obj)

  (backend/run #'inspector-test)
  ,)

  
(comment
  (require '[pl.danieljanus.tagsoup :as tagsoup])
  (require '[clojure.data.json :as json])

  (inspect (read-string (slurp "deps.edn")))

  (inspect ((requiring-resolve 'pl.danieljanus.tagsoup/parse-string) (slurp "https://clojure.org/reference/reader"))
           {:height 10})

  (inspect (gen/generate (s/gen ::anything)))

  (inspect (json/read-str (slurp "https://raw.githubusercontent.com/dreadwarrior/ext-giftcertificates/5e447a7316aea57a372203f2aa8de5aef3af671a/ExtensionBuilder.json")) )

  ,
)

(comment
  (def a (atom nil))
  (def b (atom a))
  (reset! a b)
  (inspect a)
  ,)
