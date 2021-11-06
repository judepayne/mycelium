(ns mycelium.core
  (:require [clojure.string :as str]))


(def ^:private escapable-characters "\\|{}\"")


(defn- escape-string
  "Escape characters that are significant for the dot format."
  [s]
  (reduce
    #(str/replace %1 (str %2) (str "\\" %2))
    s
    escapable-characters))

;;; generate consistent, random names for nodes & clusters

(def ^:private node->id
  (memoize (fn [_] (gensym "node"))))


(def ^:private cluster->id
  (memoize (fn [_] (gensym "cluster"))))


;;indentation of the dot string


(def ^:private indentation-counter (atom 0))


(def ^:private tab 4)


(defn- indent! [] (swap! indentation-counter #(+ tab %)) nil)


(defn- outdent! [] (swap! indentation-counter #(- % tab)) nil)


(defn- indentation [] (apply str (repeat @indentation-counter \space)))

;;;

(def ^:private default-options
  {:dpi 62})


(def ^:private default-node-options
  {})


(def ^:private default-edge-options
  {})

;;;

(def ^:private option-translations
  {:vertical? [:rankdir {true :TP, false :LR}]})


(defn- translate-options [m]
  (->> m
    (map
      (fn [[k v]]
        (if-let [[k* f] (option-translations k)]
          (when-not (contains? m k*)
            [k* (f v)])
          [k v])))
    (remove nil?)
    (into {})))

;;;

(defn- ->literal [s]
  ^::literal [s])


(defn- literal? [x]
  (-> x meta ::literal))


(defn- unwrap-literal [x]
  (if (literal? x)
    (first x)
    x))

;;;

(defn- format-options-value [v]
  (let [v-str (str v)]
    (cond
      ;; special form for attributes that refer to clusters or nodes, like ltail and lhead
      (and (vector? v) (= (first v) :cluster))  (cluster->id (second v))
      (and (vector? v) (= (first v) :node))     (node->id (second v))
      ;; handle html like labels which don't work if wrapped in quotes
      (str/starts-with? v-str "<<") v-str
      (string? v) (str \" (escape-string v) \")
      (keyword? v) (name v)
      (coll? v) (if (literal? v)
                  (str "\"" (unwrap-literal v) "\"")
                  (str "\""
                       (->> v
                            (map format-options-value)
                            (interpose ",")
                            (apply str))
                       "\""))
      :else (str v))))


(defn- format-label [label]
  (cond
    (sequential? label)
    (->> label
      (map #(str "{ " (-> % format-label unwrap-literal) " }"))
      (interpose " | ")
      (apply str)
      ->literal)

    (string? label)
    label

    (nil? label)
    ""

    :else
    (pr-str label)))


(def special-attributes #{:ltail "ltail" :lhead "lhead"})


(defn special-attribute? [at] (contains? special-attributes at))


(defn process-special [at v options]
  (println options)
  (cond
    (contains? #{:ltail "ltail" :lhead "lhead"} at) 
    (cond
      ((:node? options) v)  (node->id v)
      ((:cluster? options) v)  (cluster->id v)
      :else v)

    :else v))


(defn- format-options [m separator]
  (->>
    (update-in m [:label] #(when % (format-label %)))
    (remove (comp nil? second))
    (map
      (fn [[k v]]
        (str (name k) "=" (format-options-value v))))
    (interpose separator)
    (apply str)))


(defn- format-edge [src dst {:keys [directed?] :as options} & {:keys [cluster? node?] :as context}]
  (let [options (update-in options [:label] #(or % ""))]
    (str src
      (if directed?
        " -> "
        " -- ")
      dst
      "[" (format-options (dissoc options :directed?) ", ") "]")))


(defn- format-node [id {:keys [label shape] :as options}]
  (let [shape (or shape
                (when (sequential? label)
                  :record))
        options (assoc options
                  :label (or label "")
                  :shape shape)]
    (str id "["
      (format-options options ", ")
      "]")))


(defn- format-rank [ids]
  (apply str "{rank=same; "
         (concat (interpose ", " ids) ["}"])))

;;;


(defn- start-graph [options directed?]
  (str
   (if directed? "digraph {\n" "graph {\n")
   (indent!)
   (indentation)
   "graph["
   (-> (merge default-options options)
       (assoc-in [:fontname] "Monospace")
       (dissoc :edge :node)
       translate-options
       (format-options ", "))
   "]\n"
   (indentation)
   (str "node["
        (-> (merge {:fontname "Monospace"} (:node options))
            translate-options
            (format-options ", "))
        "]\n")
   (indentation)
   (str "edge["
        (-> (merge {:fontname "Monospace"} (:edge options))
            translate-options
            (format-options ", "))
        "]")))


;;; Clusters

;;; build a tree of the cluster edges

(defn- cluster-edges->map [edges]
  (reduce
   (fn [tree [c p]]
     (if (nil? p)
       (assoc-in tree [c] nil)   ;; case when clusters that have no parent
       (update-in tree [p]
                  (fn [s] (set (conj s c))))))
   {}
   edges))


(defn- roots [m]
  (let [children (set (apply concat (vals m)))]
    (remove #(contains? children %) (keys m))))


(defn- build-subtree [node m]
  (let [children (get m node)]
    (when children
      (reduce (fn [acc cur]
                (assoc acc cur (build-subtree cur m)))
              {}
              children))))


(defn- build-tree [edges]
  (let [m (cluster-edges->map edges)
        roots (roots m)]
    (reduce
     (fn [acc cur]
       (assoc acc cur (build-subtree cur m)))
     {}
     roots)))


;; layout

(defn- layout-nodes [cluster cluster->nodes node->descriptor]
  (apply str
         (map
          (fn [n]
            (str (indentation)
                 (format-node (node->id n) (node->descriptor n)) \newline))
          (cluster->nodes cluster))))


(defn- layout-ranks [ranks]
  (str
   (apply str
          (->> ranks
               (mapv
                (fn [r]
                  (str
                   (indentation)
                   (format-rank
                    (map node->id r)))))))
   \newline))


;;; subgraphs/ clusters

(defn- subgraphs [cluster subtree
                 cluster->descriptor cluster->nodes
                 cluster->ranks node->descriptor]
  (apply str

         ;; subgraph
         (str \newline (indentation)
              "subgraph " (cluster->id cluster) " {")
         \newline
         (indent!)
         (indentation)
         (-> (cluster->descriptor cluster)
             translate-options
             (format-options " "))
         \newline

         ;; nodes
         (layout-nodes cluster cluster->nodes node->descriptor)

         ;; ranks
         (when-let [ranks (cluster->ranks cluster)]
           (layout-ranks ranks))

         ;; any recursion into other subgraphs
         (cond
           (nil? subtree)    nil

           (map? subtree)    (map
                              (fn [[cluster subtree]]
                                (str
                                 (subgraphs cluster subtree
                                            cluster->descriptor cluster->nodes
                                            cluster->ranks node->descriptor)
                                 (outdent!)
                                 (indentation)
                                 "}"
                                 \newline))
                              subtree))))

;;; the public function `graph->dot`


(defn graph->dot
  [nodes edges
   & {:keys [directed?
             options
             node->descriptor
             edge->descriptor
             edge->src
             edge->dest
             cluster->parent
             node->cluster
             cluster->descriptor
             cluster->ranks]
      :or {directed? true
            node->descriptor (constantly nil)
           edge->descriptor (constantly nil)
           edge->src #(or (first %) (:src %))
           edge->dest #(or (second %) (:dest %))
           cluster->parent (constantly nil)
           node->cluster (constantly nil)
           cluster->descriptor (constantly nil)
           cluster->ranks (constantly nil)}
      :as graph-descriptor}]

  (let [cluster->nodes (when node->cluster
                         (group-by node->cluster nodes))

        node->descriptor (fn [n] (merge default-node-options (-> options :node) (node->descriptor n)))

        cluster-tree (when (and cluster->nodes cluster->parent)
                       (-> cluster->nodes
                           (dissoc nil)   ;; process nodes not in clusters later
                           keys
                           (->> (map (fn [c] [c (cluster->parent c)])))
                           build-tree))

        node? #(contains? (set nodes) %)]

    (reset! indentation-counter 0)

    (str

     ;; start the graph
     (start-graph options directed?)
     \newline

     ;; clusters
     (apply str
            (map (fn [[cluster children]]
                   (str
                    (subgraphs cluster children
                               cluster->descriptor cluster->nodes
                               cluster->ranks node->descriptor)
                    (outdent!) (indentation) "}" \newline))
                 cluster-tree))
     \newline

     ;; any nodes not in clusters
     (layout-nodes nil cluster->nodes node->descriptor)
     \newline

     ;; ranks
     (when-let [ranks (cluster->ranks nil)]
       (layout-ranks ranks))

     ;; edges
     (apply str
            (map
             (fn [e]
               (let [src (edge->src e)
                     dest (edge->dest e)
                     descriptor (edge->descriptor e)
                     src-id (if (node? src) (node->id src) (cluster->id src))
                     dest-id (if (node? dest) (node->id dest) (cluster->id dest))]
                 (str
                  (indentation)
                  (format-edge src-id dest-id (assoc descriptor :directed? directed?))
                  \newline)))
             edges))

     ;;close the graph
     (outdent!)
     (indentation)
     "}")))
