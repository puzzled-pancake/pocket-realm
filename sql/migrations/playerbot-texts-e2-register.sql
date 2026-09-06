-- 0414 (rp-depth-fix-plan v2.3 s6 E2): the texts.sql register audit.
--
-- Idempotent row-content UPDATEs for the reachable hello / goodbye /
-- hello_follow rows whose register drifted into modern chat-speak,
-- office-speak, and the dangling-initiative family (the hello_follow
-- rows that assert an unproposed action at master-acquisition). The
-- shipped 0394 texts.sql entry stays byte-identical: editing a shipped
-- entry breaks the on-device ledger hash check (the s0.11 append-only
-- law; the 0413 precedent), so the audit rides this tail entry instead.
-- Fresh provisions replay the manifest in order and land corrected;
-- upgraded databases apply it once through the ledger. No key renames -
-- A9's once-per-name miss-log diagnostic keeps its ground. The 533 dead
-- rows (taunt/loot/aoe pools with no reader) stay excluded.

UPDATE `ai_playerbot_texts` SET `text` = 'Well met! I will walk with you.' WHERE `name` = 'hello_follow' AND `text` = 'Hello, I follow you!';
UPDATE `ai_playerbot_texts` SET `text` = 'Well met. I will keep pace with you.' WHERE `name` = 'hello_follow' AND `text` = 'Hello, lead the way!';
UPDATE `ai_playerbot_texts` SET `text` = 'Greetings. I am with you.' WHERE `name` = 'hello_follow' AND `text` = 'Hi, lead the way!';
UPDATE `ai_playerbot_texts` SET `text` = 'Hail! I shall walk beside you.' WHERE `name` = 'hello_follow' AND `text` = 'Hey, I\'m following you now!';
UPDATE `ai_playerbot_texts` SET `text` = 'At your side, then - lead as you like.' WHERE `name` = 'hello_follow' AND `text` = 'Ready when you are, I\'ll follow!';
UPDATE `ai_playerbot_texts` SET `text` = 'Onward, then. I am right behind you.' WHERE `name` = 'hello_follow' AND `text` = 'Lead on, I\'m right behind you!';
UPDATE `ai_playerbot_texts` SET `text` = 'Good morning, or good evening - whichever finds you.' WHERE `name` = 'hello' AND `text` = 'Good morning/afternoon/evening.';
UPDATE `ai_playerbot_texts` SET `text` = 'Well met. I could not help but notice you arriving.' WHERE `name` = 'hello' AND `text` = 'Hey, I couldn’t help but notice you, how’s it going?';
UPDATE `ai_playerbot_texts` SET `text` = 'Hail. Do you have the hour? A traveler loses track of it.' WHERE `name` = 'hello' AND `text` = 'Hello, could you tell me the time, please? I’m just making conversation.';
UPDATE `ai_playerbot_texts` SET `text` = 'I hope the road has been kind to you.' WHERE `name` = 'hello' AND `text` = 'I hope this greeting finds you well.';
UPDATE `ai_playerbot_texts` SET `text` = 'I hope the day has earned its keep for you.' WHERE `name` = 'hello' AND `text` = 'I hope you’re having a productive day.';
UPDATE `ai_playerbot_texts` SET `text` = 'Welcome, traveler. What do you seek?' WHERE `name` = 'hello' AND `text` = 'Welcome. How may I assist you today?';
UPDATE `ai_playerbot_texts` SET `text` = 'Well met, and thank you for the pause.' WHERE `name` = 'hello' AND `text` = 'Thank you for taking the time to meet with me.';
UPDATE `ai_playerbot_texts` SET `text` = 'An honor to share the road with you.' WHERE `name` = 'hello' AND `text` = 'It’s an honor to be here with you.';
UPDATE `ai_playerbot_texts` SET `text` = 'Salutations! The crowds are fine company today.' WHERE `name` = 'hello' AND `text` = 'Salutations! Are you enjoying the event?';
UPDATE `ai_playerbot_texts` SET `text` = 'Good day! What brings you this way?' WHERE `name` = 'hello' AND `text` = 'Good day! How can I help you?';
UPDATE `ai_playerbot_texts` SET `text` = 'Hey, how does the day treat you?' WHERE `name` = 'hello' AND `text` = 'Hey, how’s your day going?';
UPDATE `ai_playerbot_texts` SET `text` = 'What news on the road?' WHERE `name` = 'hello' AND `text` = 'What\'s up!';
UPDATE `ai_playerbot_texts` SET `text` = 'How fares the road for you?' WHERE `name` = 'hello' AND `text` = 'How’s it going?';
UPDATE `ai_playerbot_texts` SET `text` = 'Safe travels, friend.' WHERE `name` = 'goodbye' AND `text` = 'Toodledoo';
UPDATE `ai_playerbot_texts` SET `text` = 'Until next time.' WHERE `name` = 'goodbye' AND `text` = 'Ciao';
UPDATE `ai_playerbot_texts` SET `text` = 'Until then, friend.' WHERE `name` = 'goodbye' AND `text` = 'Ta ta for now';
UPDATE `ai_playerbot_texts` SET `text` = 'See you down the road!' WHERE `name` = 'goodbye' AND `text` = 'See you later alligator!';
UPDATE `ai_playerbot_texts` SET `text` = 'Go on, then. I will say no more.' WHERE `name` = 'goodbye' AND `text` = 'Catch you later? No, don’t. Please.';
UPDATE `ai_playerbot_texts` SET `text` = 'So long. I will remember this.' WHERE `name` = 'goodbye' AND `text` = 'So long, and thanks for absolutely nothing.';
UPDATE `ai_playerbot_texts` SET `text` = 'Enough of this. Farewell.' WHERE `name` = 'goodbye' AND `text` = 'This conversation is over. Goodbye.';
UPDATE `ai_playerbot_texts` SET `text` = 'We will settle this properly another day. Farewell.' WHERE `name` = 'goodbye' AND `text` = 'See you in court, maybe? Have a nice day.';
UPDATE `ai_playerbot_texts` SET `text` = 'You may go now. Farewell.' WHERE `name` = 'goodbye' AND `text` = 'Your presence is no longer required. Goodbye.';
UPDATE `ai_playerbot_texts` SET `text` = 'Be well.' WHERE `name` = 'goodbye' AND `text` = 'Cheers';
UPDATE `ai_playerbot_texts` SET `text` = 'Fare well.' WHERE `name` = 'goodbye' AND `text` = 'Cheerio';
