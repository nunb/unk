;; unk.clj -- A pluggable, manipulable memoization library for Clojure

;; by Michael Fogus - <http://fogus.me/fun/unk>
;; Feb. 2011

; Copyright (c) Michael Fogus, 2011. All rights reserved.  The use
; and distribution terms for this software are covered by the Eclipse
; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
; which can be found in the file COPYING the root of this
; distribution.  By using this software in any fashion, you are
; agreeing to be bound by the terms of this license.  You must not
; remove this notice, or any other, from this software.

(ns fogus.unk
  "unk is a memoization library offering functionality above Clojure's core `memoize`
   function in the following ways:

   - Pluggable memoization
   - Manipulable memoization cache

   ## Pluggable memoization

   unk allows for different back-end cache implmentations to be used as appropriate without
   changing the memoization modus operandi.

   ## Manipulable memoization

   Because unk allows you to access a function's memoization store, you do interesting things like
   clear it, modify it, and save it for later.
  "
  {:author "fogus"})

;; # Protocols and Types

(defprotocol CacheProtocol
  "This is the protocol describing the basic cache capability."
  (lookup  [cache e]
   "Retrieve the value associated with `e` if it exists")
  (has?    [cache e]
   "Checks if the cache contains a value associtaed with `e`")
  (hit     [cache e]
   "Is meant to be called if the cache is determined to contain a value
   associated with `e`")
  (miss    [cache e ret]
   "Is meant to be called if the cache is determined to **not** contain a
   value associated with `e`")
  (seed    [cache base]
   "Is used to signal that the cache should be created with a seed.
   The contract is that said cache should return an instance of its
   own type."))

(deftype BasicCache [cache]
  CacheProtocol
  (lookup [_ item]
    (get cache item))
  (has? [_ item]
    (contains? cache item))
  (hit [this item] this)
  (miss [_ item result]
    (BasicCache. (assoc cache item result)))
  (seed [_ base]
    (BasicCache. base))
  Object
  (toString [_] (str cache)))

(deftype FIFOCache [cache q limit]
  CacheProtocol
  (lookup [_ item]
    (get cache item))
  (has? [_ item]
    (contains? cache item))
  (hit [this item]
    this)
  (miss [_ item result]
    (let [k (peek q)]
      (FIFOCache. (-> cache (dissoc k) (assoc item result))
                  (-> q pop (conj item))
                  limit)))
  (seed [_ base]
    (FIFOCache. base
                (into clojure.lang.PersistentQueue/EMPTY
                      (repeat limit :free))
                limit))
  Object
  (toString [_]
    (str cache \, \space (pr-str q))))

(defmethod print-method clojure.lang.PersistentQueue [q, w]
  (print-method '<- w)
  (print-method (seq q) w)
  (print-method '-< w))

(deftype LRUCache [cache lru tick limit]
  CacheProtocol
  (lookup [_ item]
    (get cache item))
  (has? [_ item]
    (contains? cache item))
  (hit [_ item]
    (let [tick+ (inc tick)]
      (LRUCache. cache
                 (assoc lru item tick+)
                 tick+
                 limit)))
  (miss [_ item result]
    (let [tick+ (inc tick)
          k (apply min-key lru (keys lru))]
      (LRUCache. (-> cache (dissoc k) (assoc item result))
                 (-> lru (dissoc k) (assoc item tick+))
                 tick+
                 limit)))
  (seed [_ base]
    (LRUCache. base
               (into {} (for [x (range (- limit) 0)] [x x]))
               0
               limit))
  Object
  (toString [_]
    (str cache \, \space lru \, \space tick \, \space limit)))

(declare dissoc-dead)

(deftype TTLCache [cache ttl limit]
  CacheProtocol
  (lookup [_ item]
    (get cache item))
  (has? [_ item]
    (when-let [t (get ttl item)]
      (< (- (System/currentTimeMillis)
            t)
         limit)))
  (hit [this item] this)
  (miss [this item result]
    (let [now  (System/currentTimeMillis)
          this (dissoc-dead this now)]
      (TTLCache. (assoc (:cache this) item result)
                 (assoc (:ttl this) item now)
                 limit)))
  (seed [_ base]
    (TTLCache. {} {} limit))
  
  Object
  (toString [_]
    (str cache \, \space ttl \, \space limit)))

(defn- dissoc-dead
  [state now]
  (let [ks (map key (filter #(> (- now (val %)) (:limit state))
                            (:ttl state)))
        dissoc-ks #(apply dissoc % ks)]
    (TTLCache. (dissoc-ks (:cache state))
               (dissoc-ks (:ttl state))
               (:limit state))))


(deftype LUCache [cache lu limit]
  CacheProtocol
  (lookup [_ item]
    (get cache item))
  (has? [_ item]
    (contains? cache item))
  (hit [_ item]
    (LUCache. cache (update-in lu [item] inc) limit))
  (miss [_ item result]
    (let [k (apply min-key lu (keys lu))]
      (LUCache. (-> cache (dissoc k) (assoc item result))
                (-> lu (dissoc k) (assoc item 0))
                limit)))
  (seed [_ base]
    (LUCache. base
              (into {} (for [x (range (- limit) 0)] [x x]))
              limit))
  
  Object
  (toString [_]
    (str cache \, \space lu \, \space limit)))

(deftype SoftCache [cache rq]
  CacheProtocol
  (lookup [_ item]
    (loop [r   (get cache item)
           val (.get r)]
      (clojure.lang.Util/clearCache rq cache)
      (if (.isEnqueued r)
        (recur r (.get r))
        val)))
  (has? [_ item]
    (contains? cache item))
  (hit [this item] this)
  (miss [_ item result]
    (SoftCache. (assoc cache item (java.lang.ref.SoftReference. result rq)) rq))
  (seed [_ base]
    (SoftCache. base rq))
  Object
  (toString [_] (str cache)))


;; # Plugging framework

(deftype PluggableMemoization [f cache]
  CacheProtocol
  (has? [_ item]
    (has? cache item))
  (hit  [_ item]
    (PluggableMemoization. f (hit cache item)))
  (miss [_ item result]
    (PluggableMemoization. f (miss cache item result)))
  (lookup [_ item]
    (lookup cache item))
  (seed [_ base]
    (PluggableMemoization. f (seed cache base)))
  Object
  (toString [_] (str cache)))


;; # Factories

(defn- basic-cache-factory
  "Returns a pluggable basic cache initialied to `base`"
  [f base]
  {:pre [(fn? f) (map? base)]}
  (PluggableMemoization. f (BasicCache. base)))

(defn- fifo-cache-factory
  "Returns a pluggable FIFO cache with the cache and FIFO queue initialied to `base` --
   the queue is filled as the values are pulled out of `seq`. (maybe this should be
   randomized?)"
  [f limit base]
  {:pre [(fn? f)
         (number? limit) (< 0 limit)
         (map? base)]}
  (PluggableMemoization. f (seed (FIFOCache. {} clojure.lang.PersistentQueue/EMPTY limit) base)))

(defn- lru-cache-factory
  "Returns a pluggable LRU cache with the cache and usage-table initialied to `base` --
   each entry is initialized with the same usage value. (maybe this should be
   randomized?)"
  [f limit base]
  {:pre [(fn? f)
         (number? limit) (< 0 limit)
         (map? base)]}
  (PluggableMemoization. f (seed (LRUCache. {} {} 0 limit) base)))

(defn- ttl-cache-factory
  "Returns a pluggable TTL cache with the cache and expiration-table initialied to `base` --
   each with the same time-to-live."
  [f ttl base]
  {:pre [(fn? f)
         (number? ttl) (< 0 ttl)
         (map? base)]}
  (PluggableMemoization. f (TTLCache. base {} ttl)))

(defn- lu-cache-factory
  "Returns a pluggable LU cache with the cache and usage-table initialied to `base`."
  [f limit base]
  {:pre [(fn? f)
         (number? limit) (< 0 limit)
         (map? base)]}
  (PluggableMemoization. f (seed (LUCache. {} {} limit) base)))

(defn- soft-cache-factory
  "Returns a pluggable soft cache initialied to `base`"
  [f base]
  {:pre [(fn? f) (map? base)]}
  (let [m  (java.util.concurrent.ConcurrentHashMap. base)
        rq (java.lang.ref.ReferenceQueue.)]
    (PluggableMemoization. f (SoftCache. m rq))))


;; # Auxilliary functions

(defn- through
  "The basic hit/miss logic for the cache system.  Clojure delays are used
   to hold the cache value."
  [cache f item]
  (if (has? cache item)
    (hit cache item)
    (miss cache item (delay (apply f item)))))

(def ^{:private true
       :doc "Returns a function's cache identity."}
  cache-id #(:unk (meta %)))


;; # Public Utilities API

(defn snapshot
  "Returns a snapshot of an unk-placed memoization cache.  By snapshot
   you can infer that what you get is only the cache contents at a
   moment in time."
  [memoized-fn]
  (when-let [cache (:unk (meta memoized-fn))]
    (into {}
          (for [[k v] (.cache (.cache @cache))]
            [(vec k) @v]))))

(defn memoized?
  "Returns true if a function has an unk-placed cache, false otherwise."
  [f]
  (boolean (:unk (meta f))))

(defn memo-clear!
  "Reaches into an unk-memoized function and clears the cache.  This is a
   destructive operation and should be used with care.

   Keep in mind that depending on what other threads or doing, an
   immediate call to `snapshot` may not yield an empty cache.  That's
   cool though, we've learned to deal with that stuff in Clojure by
   now."
  [f]
  (when-let [cache (cache-id f)]
    (swap! cache (constantly (seed @cache {})))))

(defn memo-swap!
  "Takes an unk-populated function and a map and replaces the memoization cache
   with the supplied map.  This is potentially some serious voodoo,
   since you can effectively change the semantics of a function on the fly.

       (def id (memo identity))
       (memo-swap! id '{[13] :omg})
       (id 13)
       ;=> :omg

   With great power comes ... yadda yadda yadda."
  [f base]
  (when-let [cache (cache-id f)]
    (swap! cache
           (constantly (seed @cache
                             (into {}
                                   (for [[k v] base]
                                     [k (reify
                                          clojure.lang.IDeref
                                          (deref [this] v))])))))))

(defn memo-unwrap
  [f]
  (:unk-orig (meta f)))

;; # Public memoization API

(defn build-memoizer
  "Builds a function that given a function, returns a pluggable memoized
   version of it.  `build-memoizer` Takes a cache factory function and the
   arguments that it takes.  At least one of those functions should be the
   function to be memoized."
  ([cache-factory f & args]
     (let [cache (atom (apply cache-factory f args))]
       (with-meta
        (fn [& args] 
          (let [cs (swap! cache through f args)]
            @(lookup cs args)))
        {:unk cache
         :unk-orig f}))))

(defn memo
  "Used as a more flexible alternative to Clojure's core `memoization`
   function.  Memoized functions built using `memo` will respond to
   the core unk manipulable memoization utilities.  As a nice bonus,
   you can use `memo` in place of `memoize` without any additional
   changes.

   The default way to use this function is to simply apply a function
   that will be memoized.  Additionally, you may also supply a map
   of the form `'{[42] 42, [108] 108}` where keys are a vector 
   mapping expected argument values to arity positions.  The map values
   are the return values of the memoized function.

   You can access the memoization cache directly via the `:unk` key
   on the memoized function's metadata.  However, it is advised to
   use the unk primitives instead as implementation details may
   change over time."
  ([f] (memo f {}))
  ([f seed]
     (build-memoizer
       basic-cache-factory
       f
       seed)))

(defn memo-fifo
  "Works the same as the basic memoization function (i.e. `memo` and `core.memoize` except
   when a given threshold is breached.  Observe the following:

    (def id (memo-fifo identity 2))
    
    (id 42)
    (id 43)
    (snapshot id)
    ;=> {[42] 42, [43] 43}

   As you see, the limit of `2` has not been breached yet, but if you call again with another
   value, then it will:

    (id 44)
    (snapshot id)
    ;=> {[44] 44, [43] 43}

   That is, the oldest entry `42` is pushed out of the memoization cache.  This is the standard
   **F**irst **I**n **F**irst **O**ut behavior."
  ([f] (memo-fifo f 32 {}))
  ([f limit] (memo-fifo f limit {}))
  ([f limit base]
     (build-memoizer
       fifo-cache-factory
       f
       limit
       base)))

(defn memo-lru
  "Works the same as the basic memoization function (i.e. `memo` and `core.memoize` except
   when a given threshold is breached.  Observe the following:

    (def id (memo-lru identity 2))
    
    (id 42)
    (id 43)
    (snapshot id)
    ;=> {[42] 42, [43] 43}
    
   At this point the cache has not yet crossed the set threshold of `2`, but if you execute
   yet another call the story will change:

    (id 44)
    (snapshot id)
    ;=> {[44] 44, [43] 43}

   At this point the operation of the LRU cache looks exactly the same at the FIFO cache.
   However, the difference becomes apparent on further use:

    (id 43)
    (id 0)
    (snapshot id)
    ;=> {[0] 0, [43] 43}

   As you see, once again calling `id` with the argument `43` will expose the LRU nature
   of the underlying cache.  That is, when the threshold is passed, the cache will expel
   the **L**east **R**ecently **U**sed element in favor of the new."
  ([f] (memo-lru f 32))
  ([f limit] (memo-lru f limit {}))
  ([f limit base]
     (build-memoizer
       lru-cache-factory
       f
       limit
       base)))

(defn memo-ttl
  "Unlike many of the other unk memoization functions, `memo-ttl`'s cache policy is time-based
   rather than algortihmic or explicit.  When memoizing a function using `memo-ttl` you should
   should provide a **T**ime **T**o **L**ive parameter in milliseconds.

    (def id (memo-ttl identity 5000))

    (id 42)
    (snapshot id)
    ;=> {[42] 42}

    ... wait 5 seconds ...
    (id 43)
    (snapshot id)
    ;=> {[43] 43}

   The expired cache entries will be removed on each cache miss."
  ([f] (memo-ttl f 3000 {}))
  ([f limit] (memo-ttl f limit {}))
  ([f limit base]
     (build-memoizer
       ttl-cache-factory
       f
       limit
       {})))

(defn memo-lu
  "Similar to the implementation of memo-lru, except that this function removes all cache
   values whose usage value is smallest.

    (def id (memo-lu identity 3))

    (id 42)
    (id 42)
    (id 43)
    (id 44)
    (snapshot id)
    ;=> {[44] 44, [42] 42}

   The **L**east **U**sed values are cleared on cache misses."
  ([f] (memo-lu f 32))
  ([f limit] (memo-lu f limit {}))
  ([f limit base]
     (build-memoizer
       lu-cache-factory
       f
       limit
       base)))
