;;; .ent.el --- local ent config file -*- lexical-binding: t; -*-

;;; Commentary:

;;; Code:

;; project settings
(setq ent-project-home (file-name-directory (if load-file-name load-file-name buffer-file-name)))
(setq ent-project-name "migratus")

(require 'ent)

(ent-tasks-init)

(task 'format '() "run format" '(lambda (&optional x) "bin/format"))

(task 'lint '() "run lint" '(lambda (&optional x) "bin/lint"))

(task 'test '() "run tests" '(lambda (&optional x) "bin/test"))

(task 'libupdate '() "check for new versions" '(lambda (&optional x) "clojure -M:outdated"))

(provide '.ent)
;;; .ent.el ends here

;; Local Variables:
;; no-byte-compile: t
;; no-update-autoloads: t
;; End:
