(ns plumbing.core
  "Utility belt for Clojure in the wild"
  (:require
   [plumbing.fnk.schema :as schema]
   [plumbing.fnk.pfnk :as pfnk]
   [plumbing.fnk.impl :as fnk-impl]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Maps

(defmacro for-map
 "Like 'for' for building maps. Same bindings except the body should have a 
  key-expression and value-expression. If a key is repeated, the last
  value (according to \"for\" semantics) will be retained.  

  (= (for-map [i (range 2) j (range 2)] [i j] (even? (+ i j)))
     {[0 0] true, [0 1] false, [1 0] false, [1 1] true})

  An optional symbol can be passed as a first argument, which will be
  bound to the transient map containing the entries produced so far."
 ([seq-exprs key-expr val-expr]
    `(for-map ~(gensym "m") ~seq-exprs ~key-expr ~val-expr))
 ([m-sym seq-exprs key-expr val-expr]
    `(let [m-atom# (atom (transient {}))]
       (doseq ~seq-exprs
         (let [~m-sym @m-atom#]
           (reset! m-atom# (assoc! ~m-sym ~key-expr ~val-expr))))
       (persistent! @m-atom#))))

(defn map-vals
  "Build map k -> (f v) for [k v] in map m"
  [f m]
  (if (map? m)
    (persistent! (reduce-kv (fn [out-m k v] (assoc! out-m k (f v))) (transient {}) m))
    (for-map [[k v] m] k (f v))))

(defn map-keys
  "Build map (f k) -> v for [k v] in map m"
  [f m]
  (if (map? m)
    (persistent! (reduce-kv (fn [out-m k v] (assoc! out-m (f k) v)) (transient {}) m))
    (for-map [[k v] m] (f k) v)))

(defn map-from-keys
  "Build map k -> (f k) for keys in ks"
  [f ks]
  (for-map [k ks] k (f k)))

(defn map-from-vals
  "Build map (f v) -> v for vals in vs"
  [f vs]
  (for-map [v vs] (f v) v))

(defn dissoc-in
  "Dissociate this keyseq from m, removing any empty maps created as a result
   (including at the top-level)."
  [m [k & ks]]
  (when m
    (if-let [res (and ks (dissoc-in (m k) ks))]
      (assoc m k res)
      (let [res (dissoc m k)]
        (when-not (empty? res)
          res)))))

(defn keywordize-map
  "Recursively convert maps in m (including itself)
   to have keyword keys instead of string"
  [x]
  (condp instance? x
   clojure.lang.IPersistentMap
   (for-map [[k v] x]
     (if (string? k) (keyword k) k) (keywordize-map v))
   clojure.lang.IPersistentList
   (map keywordize-map x)
   clojure.lang.IPersistentVector
   (into [] (map keywordize-map x))
   x))

(defmacro lazy-get 
  "Like get but lazy about default"
  [m k d]
  `(if-let [pair# (find ~m ~k)] 
     (val pair#)
     ~d))

(defn safe-get 
  "Like get but throw an exception if not found"
  [m k] 
  (lazy-get m k (throw (IllegalArgumentException. (format "Key %s not found in %s" k m)))))

(defn safe-get-in
  "Like get-in but throws exception if not found"
  [m ks]
  (if (seq ks) 
    (recur (safe-get m (first ks)) (next ks))
    m))

(defn assoc-when
  "Like assoc but only assocs when value is truthy"
  [m & kvs]
  (assert (even? (count kvs)))
  (into m 
   (for [[k v] (partition 2 kvs)
         :when v]	 
     [k v])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Seqs

(defn aconcat
  "Like (apply concat s) but lazier (and shorter) "
  [s]
  (lazy-cat (first s) (when-let [n (next s)] (aconcat n))))

(defn unchunk
  "Takes a seqable and returns a lazy sequence that 
   is maximally lazy and doesn't realize elements due to either
   chunking or apply.
  
   Useful when you don't want chunking, for instance, 
   (first awesome-website? (map slurp +a-bunch-of-urls+))
   may slurp up to 31 unneed webpages, wherease 
   (first awesome-website? (map slurp (unchunk +a-bunch-of-urls+)))
   is guaranteed to stop slurping after the first awesome website.

  Taken from http://stackoverflow.com/questions/3407876/how-do-i-avoid-clojures-chunking-behavior-for-lazy-seqs-that-i-want-to-short-ci"
  [s]
  (when (seq s)
    (cons (first s)
          (lazy-seq (unchunk (rest s))))))

(defn sum  
  "Return sum of (f x) for each x in xs"
  ([f xs] (reduce + (map f xs)))
  ([xs] (reduce + xs)))

(defn singleton
  "returns (first xs) when xs has only 1 element"
  [xs]
  (when-let [xs (seq xs)]
    (when-not (next xs)
      (first xs))))

(defn indexed
  "Returns [idx x] for x in seqable s"
  [s]
  (map-indexed vector s))

(defn positions
  "Returns indices idx of sequence s where (f (nth s idx))"
  [f s]
  (keep-indexed (fn [i x] (when (f x) i)) s))

(defn frequencies-fast
  "Like clojure.core/frequencies, but faster.
   Uses Java's equal/hash, so may produce incorrect results if
   given values that are = but not .equal"
  [xs]
  (let [res (java.util.HashMap.)]
    (doseq [x xs]
      (.put res x (unchecked-inc (int (or (.get res x) 0)))))
    (into {} res)))
 
(defn distinct-fast 
  "Like clojure.core/distinct, but faster.
   Uses Java's equal/hash, so may produce incorrect results if
   given values that are = but not .equal"
  [xs] 
  (let [s (java.util.HashSet.)] 
    (filter #(when-not (.contains s %) (.add s %) true) xs)))

(defn distinct-by
  "Returns elements of xs which return unique
   values according to f. If multiple elements of xs return the same
   value under f, the first is returned"
  [f xs]
  (let [s (java.util.HashSet.)]
    (for [x xs
	  :let [id (f x)]
	  :when (not (.contains s id))]
      (do (.add s id)
	  x))))

(defn distinct-id 
  "Like distinct but uses reference rather than value identity, very clojurey"
  [xs]
  (let [s (java.util.IdentityHashMap.)]
    (doseq [x xs]
      (.put s x true))
    (iterator-seq (.iterator (.keySet s)))))

(defn interleave-all 
  "Analogy: partition:partition-all :: interleave:interleave-all"
  [& colls]
  (lazy-seq
   ((fn helper [seqs]
      (when (seq seqs)
        (concat (map first seqs)
                (lazy-seq (helper (keep next seqs))))))
    (keep seq colls))))

(defn count-when
  "Returns # of elements of xs where pred holds"
  [pred xs]
  (count (filter pred xs)))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Control flow

(defmacro ?>> 
  "Conditional double-arrow operation (->> nums (?>> inc-all? map inc))"
  [do-it? f & args]
  `(if ~do-it?
     (~f ~@args)
     ~(last args)))

(defmacro ?>
  "Conditional single-arrow operation (-> m (?> add-kv? assoc :k :v))"
  [arg do-it? f & rest]
  `(if ~do-it?
     (~f ~arg ~@rest)
     ~arg))

(defmacro fn->
  "Equivalent to `(fn [x] (-> x ~@body))"
  [& body] 
  `(fn [x#] (-> x# ~@body)))

(defmacro fn->>
  "Equivalent to `(fn [x] (->> x ~@body))"
  [& body] 
  `(fn [x#] (->> x# ~@body)))

(defmacro <- 
  "Converts a ->> to a ->

   (->> (range 10) (map inc) (<- (doto prn)) (reduce +))

   Jason W01fe is happy to give a talk anywhere any time on 
   the calculus of arrow macros"
  [& body]
  `(-> ~(last body) ~@(butlast body)))

(defmacro memoized-fn
  "Like fn, but memoized (including recursive calls). 

   The clojure.core memoize correctly caches recursive calls when you do a top-level def
   of your memozied function, but if you want an annoymous fibonacci function, you must use
   memoized-fn rather than memoize to cache the recursive calls."
  [name args & body]
   `(let [a# (atom {})]
      (fn ~name ~args
        (let [m# @a#
              args# ~args]
          (if-let [[_# v#] (find m# args#)]
            v#
            (let [v# (do ~@body)]
              (swap! a# assoc args# v#)
              v#))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Miscellaneous

(defn swap-pair!
  "Like swap! but returns a pair [old-val new-val]"
  ([a f]
     (loop []
       (let [old-val @a
             new-val (f old-val)]
         (if (compare-and-set! a old-val new-val)
           [old-val new-val]
           (recur)))))
  ([a f & args]
     (swap-pair! a #(apply f % args))))

(defn get-and-set!
  "Like reset! but returns old-val"
  [a new-val]
  (first (swap-pair! a (constantly new-val))))

(defn millis ^long []
  (System/currentTimeMillis))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; fnk

(defmacro letk
  "Keyword let.  Accepts an interleaved sequence of binding forms and map forms like:
   (letk [[a {b 2} [:e f g] c d {e 4} :as m & more] a-map ...] & body)
   a, c, d, and e are required keywords, and letk will barf if not in a-map.
   b and e are optional, and will be bound to default values if not present.
   f and g are required keys in the map found under :e.
   m will be bound to the entire map (a-map).
   more will be bound to all the unbound keys (ie (dissoc a-map :a :b :c :d :e)).
   :as and & are both optional, but must be at the end in the specified order if present."
  [bindings & body]
  (schema/assert-iae (vector? bindings) "Letk binding must be a vector")
  (schema/assert-iae (even? (count bindings)) "Letk binding must have even number of elements")
  (first
   (reduce
    (fn [body [f e]]
      [(first (fnk-impl/letk* f e body))])
    body
    (reverse (partition 2 bindings)))))

(defmacro fnk
  "Keyword fn, using letk.  Stores input and output schemata in metadata.
   Fn accepts a single explicit map i.e., (f {:foo :bar})
   Explicit top-level map structure will be recorded in output spec, or
   to capture implicit structure use ^{:output-schema output-schema-spec} 
   metadata on the binding form."
  [& args]
  (let [[name? [bind & body]] (if (symbol? (first args))
                                [(first args) (next args)]
                                [nil args])]
    (fnk-impl/fnk* name? bind body)))

(defmacro defnk
  "Analogy: fn:fnk :: defn::defnk"
  [name & args]
  (let [take-if (fn [p s] (if (p (first s)) [(first s) (next s)] [nil s]))
        [docstring? args] (take-if string? args)
        [attr-map? [bind & body]] (take-if map? args)]
    (schema/assert-iae (symbol? name) "Name for defnk is not a symbol: %s" name)
    (let [f (fnk-impl/fnk* name bind body)]
      `(def ~(with-meta name (assoc-when (or attr-map? {}) :doc docstring?))
         ~f))))

(set! *warn-on-reflection* false)
