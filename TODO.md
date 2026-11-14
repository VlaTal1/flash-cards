# TODO

## Learning logic
- [ ] **Explain "—" on grade buttons.** When a card isn't due yet (free practice), Hard/Good/Easy don't change its schedule and show "—". Add a hint above the buttons, e.g. "Not due yet (in 5 d). Grades won't move the date; only Forgot brings it back sooner."
- [ ] **Decide: separate progress per direction (SK→UK / UK→SK)?** Recognition is easier than recall, so each direction could have its own level and review date. Downsides: about twice as many reviews; existing levels (from Flashcards Maker) must be assigned to one direction.

## Backups
- [ ] **Include app settings in the JSON backup** (daily goal, new words per day, reminder, language, theme), so moving to a new phone restores everything. The streak depends on the daily goal.

## Verification
- [ ] **Check on the phone** that the next card's answer no longer flashes after grading in Flashcards mode (fixed in `FlashcardsGame`, not yet verified on the device).
- [ ] **Review translations** (uk / ru / sk), especially the Slovak UI texts in `res/values-sk/strings.xml`.

## Release / repository
- [ ] Push to GitHub (`git push -u origin main`).
- [ ] Use a real signing key before publishing anywhere (release builds are currently signed with the debug key).
