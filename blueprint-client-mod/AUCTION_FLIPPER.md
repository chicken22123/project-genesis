# Auction Flipper

A module that opens the auction house, works out what things are worth from the
listings themselves, buys the ones priced below that, and relists them.

The defaults are aimed at DonutSMP - `$` prices with `k`/`m`/`b`/`t` suffixes,
`/ah` to browse, `/ah sell <price>` to list, an anvil that reloads the page -
but every one of them is a setting, so any chest based auction house is a matter
of adjusting the wording.

Three toggles come with it, in the Blueprint menu (Right Shift by default):

| Module | Category | What it does |
| --- | --- | --- |
| Auction Flipper | Economy | The whole loop: browse, price, buy, relist. |
| Flip Dry Run | Economy | Safety catch. The flipper still prices everything and shows what it *would* buy, but never clicks buy or sell. |
| Flip Stats | HUD | One line: stage, scans, flips, money spent, expected profit. |


**Switch Flip Dry Run on first.** It costs nothing and it is the only way to
find out whether the wording defaults match your server. The FLIPPER button in
the menu's top bar opens everything below.

## Before you turn it on

This plays the game for you. DonutSMP, like almost every server with an economy
worth flipping in, bans auction house macros - so running it puts the account at
risk. That is the deal; it is not something the mod can soften, and no amount of
careful maths changes it.

## The loop

1. Send the browse command (`/ah` by default) and wait for the browser window.
2. Read every slot on the page: item name, rarity line, buy it now price.
3. Feed the cheapest copy of each item into the price model.
4. Score every listing. If one clears all the thresholds, click it and press
   whatever buy and confirm buttons the following screens hold.
5. Once the item is in the inventory, put it in hand and send
   `/ah sell <price>`, just under the cheapest competing copy. Servers with no
   sell command are covered further down.
6. Otherwise click the anvil to reload the page, and go back to step 2.

Nothing else is ever clicked. The flipper only touches a slot it has priced, or
a slot whose item name is on one of the button lists in the config, so a screen
it does not recognise leaves it idling rather than clicking around blindly.

## Stacks and what counts as the same item

Two things about a vanilla economy would quietly lose money if the flipper
ignored them, so it does not.

**Prices are per item, not per listing.** A stack of 64 at `$3.84m` and a single
at `$60k` are the same price, and the model stores the second number. Profit is
then worked out for the whole listing, so a stack is compared against a stack.

**The name is not the item.** A Sharpness V netherite sword and a plain one look
identical in a list; so do a full shulker box and an empty one. The key an item
is filed under folds in what it actually is - the item type, its enchantments,
stored enchantments on a book, potion contents, and what is inside a shulker -
so those never get priced off each other. The lore is deliberately left out of
that key, because the auction house writes the price and the seller into it, and
it therefore differs on every listing of the same thing.

## What it is allowed to buy

Before any of the maths runs, three lists decide whether an item is even a
candidate. All of them are on the **WHAT TO BUY** column of the FLIPPER screen,
and all of them are plain comma separated lists.

```
flip.neverBuy   = dirt, cobblestone, * spawn egg
flip.onlyBuy    =
flip.priceRules = elytra: 5m-80m, netherite ingot: 500k-15m, tnt: -20m
```

**Never buy** is the blacklist: those items are refused however good the numbers
look. It ships with `dirt, cobblestone` in it, which you are welcome to clear.

**Only buy** is the opposite: set it and nothing else is bought at all. Leave it
empty and everything not blacklisted is fair game.

Names are matched as **whole phrases**, so `dirt` matches "Dirt" and "64x Dirt"
but not "Dirty Sword". A `*` stands for any run of characters, so `* spawn egg`
catches every spawn egg, `shulker*` every shulker box, and `*sword*` anything
with sword in the name.

**Price rules** put a range on one item: `name: low-high`, either end optional,
in whatever shorthand you like - `100k`, `2.5m`, `80m`. A single number is a
ceiling, so `bread: 5k` means never pay more than five thousand for bread, and
an open top (`elytra: 5m-`) means no ceiling at all for that item.

> Price rules are **the price of one of the item**, not of the stack, so a rule
> reads the same whether the listing is a single elytra or sixty four of them.
> The two settings that cap a whole listing are `flip.minListingPrice` and
> `flip.maxSpendPerItem`; set either to 0 to remove it. The ceiling starts at
> $250m, high enough for elytras and netherite, with the session budget as the
> real backstop - so raise or clear it rather than working around it.

Anything refused this way shows up in the panel with the rest -
`42 listings: 12 on the never-buy list, 3 outside its price range, 2 worth
buying` - so a rule that is quietly eating everything is visible rather than
mysterious.

## Why it will not buy your dirt

The trap in an auction house is that **an asking price is not a sale price**.
Anyone can list dirt for $2,000 and leave it there. A flipper that reads that as
"dirt is worth $2,000" will happily pay $1,000 for dirt, congratulate itself on
a 90% margin, and then own some dirt.

So before the size of a margin is even looked at, the listing has to get past a
row of gates, each asking for a different kind of evidence that the resale price
is real:

| Gate | What it asks | What it stops |
| --- | --- | --- |
| **Distinct listings** | Have several *different* listings of this item been seen? A listing is recognised by its price and its seller, so the same one on twenty refreshes counts once. | One planted listing looking like twenty pieces of evidence. |
| **Different sellers** | Have at least two different people listed it? | One person listing the same lie ten times. Where the server does not show sellers, twice as many distinct listings are demanded instead. |
| **Depth** | Are there copies on the page right now to undercut? | Buying something with no established price and no one to sell it against. |
| **Churn** | Do listings of this item actually come and go? | A price that has sat untouched since it was first seen - which is a price nobody is paying. |
| **Confidence and spread** | Is the evidence recent, plentiful and in agreement? | Trading on one stale sighting, or on a market that cannot decide. |

The dirt in the example fails the first gate outright: twenty refreshes of one
listing is one listing. If the same person lists it three times, it fails the
second. If two people list it and nothing ever moves, it fails the third. That
is three independent reasons it never gets bought, and the same three protect
every other item on the page.

## The maths

The fair value is not the average and it is not the middle. It is the price
**thirty per cent of the way up the asks**, because selling something means
beating the cheapest seller, not the average one. Optimistic prices sit in the
tail above that and cannot drag it up; one desperate listing at the bottom
cannot drag it down either.

Spread is measured with the median absolute deviation - one silly listing cannot
move it - and samples far enough out are dropped as outliers: three scaled
deviations, or five per cent of the median, whichever is the wider net. The five
per cent floor matters, because a few samples that happen to agree closely make
the deviation tiny, and without it the next honest listing a couple of per cent
away gets thrown out.

Then, for `count` of an item the model values at `v` each, with the cheapest
rival copy on the page asking `competitor` each:

```
ceiling = min(v, competitor)                    nobody pays more than the cheapest
haircut = spread + (1 - churn) x stale discount how much of that to disbelieve
sale    = ceiling x count x (1 - undercut) x (1 - haircut)
net     = sale x (1 - tax)
profit  = net - price
margin  = profit / price
score   = profit x confidence x (0.5 + 0.5 x supply)
```

**The haircut is the difference between a sum that looks profitable and one that
is.** The resale estimate is cut by how much the market disagrees with itself
and by how little it moves, so a sluggish item has to show a much bigger gap
before it clears the margin test, while a busy item with a tight spread is
barely touched at all.

Confidence is the model saying how much it means the number, and every part of
the evidence goes into it:

```
confidence = n/(n+3) x exp(-age/45min) x (1 - spread) x sellers/(sellers+1)x1.5 x (0.4 + 0.6 x churn)
             enough     recent           agreeing       from several people      in a market that moves
```

Scoring by profit alone would keep picking the one enormous margin the model is
least sure about - exactly the listing most likely to be bait. Multiplying by
confidence prefers the flip most likely to be real, and the supply term leans
towards items that appear on most pages, because those are the ones that sell on
again quickly.

Two more guards sit on top: a margin above `flip.suspiciousMargin` (300% by
default) is refused unless the item has `flip.trustedSamples` listings behind
it, and `flip.minUnitValue` can rule out anything cheap outright.

**It buys nothing for the first minute or two.** Each item needs several
*distinct* listings, from different people, before it is priced at all, so the
first stretch is spent watching. That is the model gathering evidence, not a
fault. When it does buy, it says what the evidence was:

```
Buying 64x Diamond Block at $3.84m, reselling about $6.08m: +$2.24m (58%)
 - 9 listings from 5 sellers, 62% of them moved, estimate cut 8%
```

While it works, the panel over the auction house shows the page as the maths
sees it - `42 listings: 28 not priced yet, 9 only one seller, 3 nothing ever
moves, 2 worth buying` - which is the quickest way to tell a quiet market from a
misconfigured one.

Prices seen are kept in `config/blueprintclient-market.properties` and reloaded
next session, so the evidence only has to be gathered once. Samples older than
six hours are dropped: they describe an older market.

## Listing with /ah sell

This is the default path, and what DonutSMP takes.

The item arrives from a purchase wherever there was room, and `/ah sell` lists
whatever is in your hand, so the flipper swaps the bought item into your
selected hotbar slot first - whatever you were holding goes to where the bought
item was, so nothing is lost - and only sends the command once the held stack is
really the right one. A sell command sent a moment early lists your pickaxe.

The price is a plain whole number, rounded to something a person would type:
`ah sell 1230000`. If a confirmation menu opens, the buttons named in
`flip.sellButtons` are pressed.

**A listing counts as made when the item leaves your inventory**, not when the
chat says something. That works whatever wording the server uses, and it is the
same test the buying side uses. Two things can go wrong instead:

- the server refuses - an auction limit, a price it will not take, an empty hand
  - which is matched against `flip.listFailedMessages`. The flipper says so, and
  after a few in a row it stops rather than buying things it cannot sell on;
- nothing happens at all within eight seconds, which usually means
  `flip.sellCommand` is not this server's command.

It also refuses to buy anything while your inventory is full, since the purchase
would have nowhere to land.

## Listing where there is no sell command

Plenty of servers - Hypixel among them - have nothing like `/ah sell 1000`.
Listing something means walking a chain of menus, and the chain differs from
server to server, so the flipper reads it from `flip.sellFlow` rather than
guessing:

```
flip.sellFlow = button:manage auctions, button:create auction, item, \
                button:custom amount, price, button:create auction
```

| Step | What it does |
| --- | --- |
| `button:name` | Click the item in the open menu whose name contains `name`. |
| `item` | Click the bought item in the inventory half of the menu, which is how most auction houses take it from you. |
| `price` | Send the asking price, plainly, for a menu that asks you to type it. `price:/ah price %price%` sends a command instead. |
| `wait:800` | Stand still for that many milliseconds, for a menu that takes its time. |

Each step has its own six second timeout. If one runs out, the flipper says
which step it got stuck on and goes back to browsing rather than clicking away
at a menu it no longer follows - so a chain that half works tells you where it
stopped matching your server.

Leave it empty and `flip.sellCommand` is used instead, which is all a server
with a real sell command needs. Build the chain with Flip Dry Run on: the
flipper will not buy anything, but everything else can be watched.

## Checking the maths

The price parsing, the price model and the scoring have no Minecraft in them, so
they can be run without the game, the mappings or a Gradle build:

```
blueprint-client-mod/tools/check-flip-math.sh   the maths
blueprint-client-mod/tools/check-sources.sh     calls into thin air
```

The first compiles six classes and runs 173 checks over them - what counts as a price,
what counts as the same item, how outliers are handled, what each verdict means,
how a sell chain is read, how the shopping lists are matched, and - the ones
worth reading - a whole section of bait scenarios: a planted listing seen twenty times, one person listing the same
thing three times, a price nothing ever moves at. None of them are bought. Worth
running after changing any of the numbers.

The second is there because the mod cannot be built without Minecraft and the
Yarn mappings, and javac is no help without them: with the argument types
unresolved it never tries to resolve the calls either, so a method that was
never written sails straight through. It reads the source instead and reports
any method the mod calls on itself that nothing declares. It has already earned
its keep once.

## Settings

Press **FLIPPER** in the top bar of the Blueprint menu. Two columns - what it
may spend and what counts as a good enough flip on the left, what your server
calls things and how fast to click on the right. Click a row to type a new
value, right click a row to put it back to the default, and RESET ALL puts back
every one of them.

Everything is written to `config/blueprintclient.properties` on the way out, so
it can also be edited by hand. Defaults suit a chest based auction house with a
`/ah` browser and an anvil that reloads the page.

| Key | Default | Meaning |
| --- | --- | --- |
| `flip.currency` | `$` | What the server puts in front of its money. |
| `flip.browseCommand` | `ah` | Command that opens the browser, no slash. |
| `flip.sellCommand` | `ah sell %price%` | Listing command; `%price%` is filled in. |
| `flip.sellFlow` | empty | Menu chain for servers with no sell command; see above. |
| `flip.browseTitles` | `auction,ah browser,market` | Window titles that mean "browser". |
| `flip.buyButtons` | `buy it now,buy item right now,buy,confirm,purchase,accept` | Item names the flipper may click to buy. |
| `flip.sellButtons` | `confirm,create auction,list item,accept,yes` | Item names that finish a listing. |
| `flip.refreshButtons` | `refresh,reload,update` | Reload button names; any anvil counts too. |
| `flip.boughtMessages` | `you purchased,you bought,…` | Chat wording that means the purchase worked. |
| `flip.buyFailedMessages` | `not enough coins,already been sold,…` | Wording that means it did not. |
| `flip.listedMessages` | `auction started,you listed,…` | Wording that means the relist worked. |
| `flip.listFailedMessages` | `too many,invalid price,…` | Wording that means the sell command was refused. |
| `flip.neverBuy` | `dirt, cobblestone` | Names never to buy. Whole phrases; `*` is a wildcard. |
| `flip.onlyBuy` | empty | When set, the only names to buy. |
| `flip.priceRules` | empty | Per item ranges, e.g. `elytra: 2m-5m`. The price of one. |
| `flip.minListingPrice` | `0` | Ignore listings cheaper than this. 0 for no floor. |
| `flip.maxSpendPerItem` | `250000000` | Never click a listing above this. A stack is one listing; 0 for no ceiling. |
| `flip.sessionBudget` | `1000000000` | Stop once this much has been spent. 0 for no limit. |
| `flip.stopAfterFlips` | `0` | Stop after this many flips; 0 means no limit. |
| `flip.minProfit` | `100000` | Profit needed to bother with a flip. |
| `flip.minMargin` | `0.12` | Profit as a fraction of the buy price. |
| `flip.minConfidence` | `0.30` | How sure the model has to be. |
| `flip.minSamples` | `3` | Distinct listings before an item is priced at all. |
| `flip.minSellers` | `2` | Different people who must have listed it. |
| `flip.minDepth` | `2` | Copies that must be on the page to resell against. |
| `flip.requireChurn` | `true` | Refuse items whose listings never come and go. |
| `flip.churnHaircut` | `0.25` | How hard a market that never moves is disbelieved. |
| `flip.minUnitValue` | `0` | Ignore anything worth less than this each. |
| `flip.maxDispersion` | `0.35` | Refuse markets that disagree with themselves. |
| `flip.suspiciousMargin` | `3.0` | Above this, demand `trustedSamples` first. |
| `flip.trustedSamples` | `12` | Sightings that make a huge margin believable. |
| `flip.saleTax` | `0.05` | The cut the auction house takes when something sells. |
| `flip.undercut` | `0.03` | How far under the competition to list. |
| `flip.binOnly` | `true` | Ignore items being bid on. |
| `flip.keepRunning` | `true` | Carry on after trouble instead of switching off. |
| `flip.actionDelayMs` | `400` | Pause between clicks; each one is a server round trip. |
| `flip.buyDelayMs` | `250` | Pause between the clicks that buy something. |
| `flip.actionJitterMs` | `250` | Random extra on top of every pause. |
| `flip.refreshDelayMs` | `1000` | Pause between page reloads. |
| `flip.maxRefreshesPerMinute` | `50` | Hard cap on reloads. |

## On DonutSMP

The defaults are written for it, but three things are worth checking on the
first run, with **Flip Dry Run** on so nothing can be bought while you look:

1. **Does the panel appear over `/ah`?** If not, the window title is not one of
   `flip.browseTitles` - put whatever the window is actually called in there.
2. **Does it read the prices?** The panel says `N listings: ...`. If N is 0 the
   price wording is wrong; DonutSMP writes plain `$` amounts, which the defaults
   already read, but a changed layout would show up here first.
3. **Does it see who is selling?** The buy message says "from N sellers". If
   your listings never show a seller, the flipper silently asks for twice as
   many distinct listings instead, which is slower but just as safe.
4. **Are the budgets right for you?** The ceiling starts at $250m a listing
   with a $1b session budget, which is meant to be high enough not to refuse an
   elytra or a stack of netherite. Set them to what you can actually afford to
   have spent while you are not watching.
5. **What is the sale tax?** `flip.saleTax` defaults to 5%. Set it to whatever
   the server actually takes - too low and every flip is worth slightly less
   than the maths thinks.

Then watch the `would buy` lines for a while. They are what it would have spent
money on; if they look like sensible trades, turn Dry Run off.

Listing uses `/ah sell %price%` with the item in hand, which is what DonutSMP
takes - see "Listing with /ah sell" above for what it does and how it knows it
worked. If the server ever moves to a menu, `flip.sellFlow` covers that without
touching the mod.

## Leaving it running

It is built to be left on. The loop is browse, price, buy, relist, reload, and
it keeps turning until you switch the module off.

**Trouble does not end the session.** A menu it cannot read, a buy screen that
went somewhere unexpected, a sell command that got no answer - each of those
used to switch the module off, which meant the next twelve hours were spent
doing nothing. Now it says what happened, waits five seconds and starts the loop
again. Set `flip.keepRunning` to false if you would rather it stopped and waited
for you.

**A watchdog catches the rest.** Every step has its own timeout, but a step can
only time out if it is reached at all; anything that leaves the machine wedged
for forty five seconds restarts the loop.

**Reconnects are handled.** Changing world or server clears whatever it was
doing and starts again once things settle, rather than carrying on halfway
through a purchase on a server you have left.

**A full inventory or a spent budget pauses buying, not watching.** It keeps
reading the auction house - every page is more evidence for later - and starts
buying again by itself the moment you make room or raise the budget. The HUD
line says so:

```
Flip: browsing | up 6h12m | 14203 scans | 61 flips (9.8/h) | spent $840m | est $310m
```

Only three things actually stop it: switching the module off, hitting
`flip.stopAfterFlips`, or trouble while `flip.keepRunning` is false. Opening the
Blueprint menu, or any other screen, pauses it where it stands and it carries on
when you close it.

**Pacing.** A page reload every second, at most fifty a minute, with a random
extra on every pause. Buying is quicker than browsing - `flip.buyDelayMs`,
250ms - because somebody else is looking at the same listing. Turning those down
buys faster and looks less like a person; that trade is yours to make.
