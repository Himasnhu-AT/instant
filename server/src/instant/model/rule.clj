(ns instant.model.rule
  (:require
   [instant.jdbc.aurora :as aurora]
   [instant.jdbc.sql :as sql]
   [instant.util.json :refer [->json]]
   [instant.db.cel :as cel]
   [instant.util.exception :as ex]
   [honey.sql :as hsql])
  (:import
   (dev.cel.common CelValidationException)))

(defn put!
  ([params] (put! aurora/conn-pool params))
  ([conn {:keys [app-id code]}]
   (sql/execute-one!
    conn
    ["INSERT INTO rules (app_id, code) VALUES (?::uuid, ?::jsonb)
     ON CONFLICT (app_id) DO UPDATE SET code = excluded.code"
     app-id (->json code)])))

(defn merge!
  ([params] (merge! aurora/conn-pool params))
  ([conn {:keys [app-id code]}]
   (sql/execute-one!
    conn
    (hsql/format {:insert-into :rules
                  :values [{:app-id app-id
                            :code [:cast (->json code) :jsonb]}]
                  :on-conflict :app-id
                  :do-update-set {:code [:|| :rules.code :excluded.code]}}))))

(defn get-by-app-id
  ([params] (get-by-app-id aurora/conn-pool params))
  ([conn {:keys [app-id]}]
   (sql/select-one conn ["SELECT * FROM rules WHERE app_id = ?::uuid" app-id])))

(defn delete-by-app-id!
  ([params] (delete-by-app-id! aurora/conn-pool params))
  ([conn {:keys [app-id]}]
   (sql/do-execute!
    conn
    ["DELETE FROM rules WHERE app_id = ?::uuid" app-id])))

(defn with-binds [rule etype expr]
  (->> (get-in rule [etype "bind"])
       (partition-all 2)
       reverse
       (reduce
        (fn [body [var-name var-body]]
          (str "cel.bind(" var-name ", " var-body ", " body ")"))
        expr)))

(defn extract [rule etype action]
  (when-let [expr (get-in rule [etype "allow" action])]
    (with-binds rule etype expr)))

(defn get-issues [etype action e]
  (map (fn [cel-issue] [etype action (.getMessage cel-issue)]) (.getErrors e)))

(defn get-program! [rules etype action]
  (when-let [code (some-> rules :code (extract etype action))]
    (try
      (let [ast (cel/->ast code)]
        {:etype etype
         :action action
         :cel-ast ast
         :cel-program (cel/->program ast)})
      (catch CelValidationException e
        (ex/throw-validation-err!
         :permission
         [etype action]
         (->> (.getErrors e)
              (map (fn [cel-issue]
                     {:message (.getMessage cel-issue)}))))))))

(defn $users-validation-errors
  "Only allow users to changes the `view` rules for $users, since we don't have
   a way to create or update them from transactions."
  [rules action]
  (case action
    ("create" "update" "delete")
    (when (not= (get-in rules ["$users" "allow" action])
                "false")
      [["$users"
        action
        (format "The $users namespace is read-only. Set `$users.allow.%s` to `\"false\"`."
                action)]])

    "view" nil))

(defn validation-errors [rules]
  (->> (keys rules)
       (mapcat (fn [etype] (map (fn [action] [etype action]) ["view" "create" "update" "delete"])))
       (mapcat (fn [[etype action]]
                 (or (and (= etype "$users")
                          ($users-validation-errors rules action))
                     (try
                       (some-> (extract rules etype action) cel/->ast cel/->program)
                       nil
                       (catch CelValidationException e
                         (get-issues etype action e))))))
       (keep identity)
       (map (fn [[etype action message]]
              {:message message
               :in [etype :allow action]}))))

(comment
  (def code {"docs" {"allow" {"view" "lol"
                              "create" "true"
                              "update" "moop"}}})

  (validation-errors code))
