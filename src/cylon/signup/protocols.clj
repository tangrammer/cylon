(ns cylon.signup.protocols)

(defprotocol SignupFormRenderer
  (render-signup-form [_ req model]))

(defprotocol WelcomeRenderer
  (render-welcome [_ req model]))

(defprotocol SimpleMessageRenderer
  (render-simple-message [_ req model]))

(defprotocol RequestResetPasswordFormRenderer
  (render-request-reset-password-form [_ req model]))

(defprotocol Emailer
  (send-email [_ email subject body]))
