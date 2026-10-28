# Pachuco

Pachuco is a multiplayer dice betting game in which players bet against the House each round.

## Language

**House**:
The player who acts as the bank in a game: every other player's bet is settled against the House's roll.
_Avoid_: Bank, dealer

**Roll**:
The in-game action of a player or the House casting the dice during a round. Every hand in a round is cast the moment betting closes, so a Roll shows a hand that is already decided; nothing a player does afterwards changes it.
_Avoid_: Throw (for the action)

**Throw**:
The recorded result of a Roll: the dice values that were cast, kept after the round.
_Avoid_: Roll (for the record)

**Lobby**:
The gathering phase before every round, in which players join the table and confirm they will play.
_Avoid_: Waiting room, enough players (as a phase name)

**Joined Player**:
A player who has taken a seat at the table but has not yet confirmed they will play the coming round. A player who joins while a round is in progress is a Joined Player until the next Lobby; they never play the round already under way.
_Avoid_: Waiting player (when meaning seated)

**Ready Player**:
A Joined Player who has confirmed they will play the coming round. Only Ready Players take part in that round.
_Avoid_: Checked player, confirmed player

**Rotation**:
The order in which players take their turns and in which the House passes from one player to the next. The first round's Ready Players are shuffled to set it, and it is never shuffled again. A round's Rotation holds only that round's Ready Players: a player who wasn't ready loses their place and goes back in at the end once ready again, and players who join after the first round also go in at the end. A House that isn't ready passes the House to the next Ready Player in the Rotation.
_Avoid_: Turn order (in conversation), player order

**Full Table**:
A table whose seats are all taken, counting every seated player, whether in the Rotation or Joined.
_Avoid_: Max reached
