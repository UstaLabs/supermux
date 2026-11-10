-- An agent's html card (#104 show_html) and a user's message sent from one: JSON, like attachments.
-- card = {id, title, height?, desktop_height?} on the agent's message; from_card = {id, title} on the user's.
ALTER TABLE messages ADD COLUMN card TEXT;
ALTER TABLE messages ADD COLUMN from_card TEXT;
