(ns syme.db
  (:refer-clojure :exclude [find])
  (:require [clojure.java.jdbc :as sql]
            [clojure.java.io :as io]
            [environ.core :as env]))

(def db (env/env :database-url "postgres://localhost:5432/syme"))

(defn create [owner project description ip]
  (sql/insert-record :instances {:project project :owner owner
                                 :status "bootstrapping"
                                 :description description :ip ip}))

(defn status [owner project status]
  (sql/with-connection db
    (sql/update-values :instances
                       ["owner = ? AND project = ?" owner project]
                       {:status status})))

(defn invite [owner project invitee]
  (sql/with-query-results [instance]
    ["SELECT * FROM instances WHERE project = ? and owner = ?"
     project owner]
    (sql/insert-record :invites {:instance_id (:id instance)
                                 :invitee invitee})))

(defn find [username project-name]
  (sql/with-connection db
    (sql/with-query-results [instance]
      ["SELECT * FROM instances WHERE owner = ? AND project = ?"
       username project-name]
      ;; whatever I suck at sql
      (sql/with-query-results invitees
        ["SELECT * FROM invites WHERE instance_id = ?" (:id instance)]
        (assoc instance
          :invitees (mapv :invitee invitees))))))

;; migrations

(defn initial-schema []
  (sql/create-table "instances"
                    [:id :serial "PRIMARY KEY"]
                    [:owner :varchar "NOT NULL"]
                    [:project :varchar "NOT NULL"]
                    [:ip :varchar]
                    [:description :text]
                    [:status :varchar]
                    [:at :timestamp "NOT NULL" "DEFAULT CURRENT_TIMESTAMP"])
  (sql/create-table "invites"
                    [:id :serial "PRIMARY KEY"]
                    [:invitee :varchar "NOT NULL"]
                    [:instance_id :integer "NOT NULL"]
                    [:at :timestamp "NOT NULL" "DEFAULT CURRENT_TIMESTAMP"]))

;; migrations mechanics

(defn run-and-record [migration]
  (println "Running migration:" (:name (meta migration)))
  (migration)
  (sql/insert-values "migrations" [:name :created_at]
                     [(str (:name (meta migration)))
                      (java.sql.Timestamp. (System/currentTimeMillis))]))

(defn migrate [& migrations]
  (sql/with-connection db
    (try (sql/create-table "migrations"
                           [:name :varchar "NOT NULL"]
                           [:created_at :timestamp
                            "NOT NULL"  "DEFAULT CURRENT_TIMESTAMP"])
         (catch Exception _))
    (sql/transaction
     (let [has-run? (sql/with-query-results run ["SELECT name FROM migrations"]
                      (set (map :name run)))]
       (doseq [m migrations
               :when (not (has-run? (str (:name (meta m)))))]
         (run-and-record m))))))

(defn -main []
  (migrate #'initial-schema))