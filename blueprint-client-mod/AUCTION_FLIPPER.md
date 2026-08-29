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
| Flip Stats | HUD | One line: stage, scans, flips, coins spent, expected profit. |

**Switch Flip Dry Run on first.** It costs nothing and it is the only way to
find out whether the wording defaults match your server. The FLIPPER button in
the menu's top bar opens everything below.

## Before you turn it on

This plays the game for you. Hypixel and most other servers ban auction house
macros, so running it puts the account at risk. That is the deal; it is not
something the mod can soften.

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

## The maths

There is no price list to download. The only market data a client has is what
the auction house shows it, so the model is built from the pages it reads.

For each item it keeps the **cheapest listing seen per page**, which is the
price a seller actually has to beat. From those samples:

- the **median** is the fair value, not the average - one fat-fingered listing
  would drag an average a long way, and a flipper that believes a bad number
  buys rubbish;
- the **median absolute deviation** measures how tightly the market agrees;
- samples far enough out are dropped as outliers - three scaled deviations, or
  five per cent of the median, whichever is the wider net - and the median of
  what is left becomes the fair value `v`. The five per cent floor matters: a
  few samples that happen to agree closely make the deviation tiny, and without
  it the next honest listing a couple of per cent away gets thrown out;
- the leftover spread, `MAD / median`, is the **dispersion**.

For a listing at price `p`, with the next cheapest copy on the same page at `c`:

```
resale = min(v, c) x (1 - undercut)     what it can realistically be sold for
net    = resale x (1 - tax)             what actually lands in the purse
profit = net - p
margin = profit / p
score  = profit x confidence x (0.5 + 0.5 x supply)
```

`confidence` is the model saying how much it means the number. Three things all
have to be true for it to be high:

```
confidence = n/(n+3) x exp(-age_minutes / 45) x (1 - dispersion)
             enough        recent               market agrees
             samples       samples              with itself
```

Scoring by profit alone would keep picking the one enormous margin the model is
least sure about, which is exactly the listing most likely to be a trap.
Multiplying by confidence prefers the flip most likely to be real, and the
supply term leans towards items that show up on most pages, because those are
the ones that sell again quickly.

Two guards sit on top:

- a margin above `flip.suspiciousMargin` (300% by default) is refused unless the
  item has at least `flip.trustedSamples` sightings - a margin that large
  usually means two different items are sharing a name;
- a dispersion above `flip.maxDispersion` is refused outright: the market has
  not settled on a price, so there is no number to trade against.

While it works, the panel over the auction house shows the page as the maths
sees it - `42 listings: 28 not priced yet, 11 too new, 3 worth buying` - which
is the quickest way to tell a quiet market from a misconfigured one.

**It buys nothing for the first minute or two.** Each item needs
`flip.minSamples` sightings across separate pages before it will be priced, so
the first stretch is spent watching. That is the model warming up, not a fault.

Prices seen are kept in `config/blueprintclient-market.properties` and reloaded
next session, so the warm-up only really happens once. Samples older than six
hours are dropped: they describe an older market.

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
blueprint-client-mod/tools/check-flip-math.sh
```

It compiles five classes and runs 98 checks over them - what counts as a price,
what counts as the same item, how outliers are handled, what each verdict means,
how a sell chain is read, and that nothing is bought before the market has been
watched. Worth running
after changing any of the numbers.

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
| `flip.maxSpendPerItem` | `10000000` | Never click a listing above this. A stack is one listing. |
| `flip.sessionBudget` | `100000000` | Stop once this much has been spent. |
| `flip.stopAfterFlips` | `0` | Stop after this many flips; 0 means no limit. |
| `flip.minProfit` | `100000` | Profit needed to bother with a flip. |
| `flip.minMargin` | `0.12` | Profit as a fraction of the buy price. |
| `flip.minConfidence` | `0.35` | How sure the model has to be. |
| `flip.minSamples` | `3` | Sightings before an item is priced at all. |
| `flip.maxDispersion` | `0.35` | Refuse markets that disagree with themselves. |
| `flip.suspiciousMargin` | `3.0` | Above this, demand `trustedSamples` first. |
| `flip.trustedSamples` | `12` | Sightings that make a huge margin believable. |
| `flip.saleTax` | `0.05` | The cut the auction house takes when something sells. |
| `flip.undercut` | `0.03` | How far under the competition to list. |
| `flip.binOnly` | `true` | Ignore items being bid on. |
| `flip.actionDelayMs` | `400` | Pause between clicks; each one is a server round trip. |
| `flip.actionJitterMs` | `250` | Random extra on top of every pause. |
| `flip.refreshDelayMs` | `900` | Pause between page reloads. |
| `flip.maxRefreshesPerMinute` | `40` | Hard cap on reloads. |

## On DonutSMP

The defaults are written for it, but three things are worth checking on the
first run, with **Flip Dry Run** on so nothing can be bought while you look:

1. **Does the panel appear over `/ah`?** If not, the window title is not one of
   `flip.browseTitles` - put whatever the window is actually called in there.
2. **Does it read the prices?** The panel says `N listings: ...`. If N is 0 the
   price wording is wrong; DonutSMP writes plain `$` amounts, which the defaults
   already read, but a changed layout would show up here first.
3. **What is the sale tax?** `flip.saleTax` defaults to 5%. Set it to whatever
   the server actually takes - too low and every flip is worth slightly less
   than the maths thinks.

Then watch the `would buy` lines for a while. They are what it would have spent
money on; if they look like sensible trades, turn Dry Run off.

Listing uses `/ah sell %price%` with the item in hand, which is what DonutSMP
takes - see "Listing with /ah sell" above for what it does and how it knows it
worked. If the server ever moves to a menu, `flip.sellFlow` covers that without
touching the mod.

## When it stops by itself

- The session budget is spent, or `flip.stopAfterFlips` flips are done.
- The browse command opened nothing five times over - usually a wrong
  `flip.browseCommand`.
- Five buy screens in a row it could not work out - usually
  `flip.buyButtons` not matching what your server calls the button.

Each one switches the module off and says why in chat. Opening any Blueprint
screen, or any other menu, pauses it where it stands.
