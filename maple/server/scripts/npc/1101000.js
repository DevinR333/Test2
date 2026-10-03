/*
 * Empress Cygnus (Ereve). Offline backport of the AfterShock Ultimate Explorers: a level-120 Cygnus
 * Knight who finished "Empress's Grace" may make one Ultimate Explorer through her (the creation
 * screen opens from a get-text marker), and an Ultimate Explorer of level 70 receives the Empress's
 * Brilliant Set from her once.
 */
const UE = Java.type('offline.UltimateExplorer');
var status = -1;
var mode0;

function start() {
    var p = cm.getPlayer();
    if (UE.isUltimate(p)) {
        if (p.getLevel() >= UE.BRILLIANT_LEVEL && !UE.upgraded(p)) {
            mode0 = "upgrade";
            cm.sendYesNo("You have grown strong, my Ultimate Explorer. Your #bEmpress's Fine Set#k no longer suits your strength. Will you accept the #bEmpress's Brilliant Set#k?");
            return;
        }
        cm.sendOk("You carry the strength of your knight, #h0#. Grow stronger, and come to me again when you reach #bLevel " + UE.BRILLIANT_LEVEL + "#k.");
        cm.dispose();
        return;
    }
    var state = UE.knightState(p);
    if (state == 1) {
        mode0 = "create";
        cm.sendYesNo("You have received the Empress's grace, #h0#. A knight of your strength can pass it on to an #bUltimate Explorer#k: a new hero who begins at #bLevel " + UE.START_LEVEL + "#k with their 2nd job, my #bEmpress's Fine Set#k and #bEmpress's Might#k, and who carries your name as their #bSuccessor#k.\r\n\r\n#rYou can do this only once.#k Will you create your Ultimate Explorer now?");
        return;
    }
    if (state == 2) {
        cm.sendOk("Your successor is already out in Maple World, #h0#. I am proud of you both.");
    } else {
        cm.sendOk("Welcome to Ereve. Thank you for protecting Maple World.");
    }
    cm.dispose();
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    status++;
    if (mode0 == "upgrade") {
        var r = UE.upgrade(cm.getPlayer());
        if (r == 0) cm.sendOk("Here is the #bEmpress's Brilliant Set#k. May it protect you.");
        else if (r == -2) cm.sendOk("Please make room for 5 items in your Equip inventory first.");
        else if (r == -3) cm.sendOk("You already carry my Brilliant Set. Grow strong, my Ultimate Explorer.");
        else cm.sendOk("Come back when you reach Level " + UE.BRILLIANT_LEVEL + ".");
        cm.dispose();
        return;
    }
    if (mode0 == "create") {
        if (status == 0) {
            cm.sendGetText(UE.CREATOR);
            return;
        }
        var name = new java.lang.StringBuilder();
        var v = UE.parseChoice(cm.getText(), name);
        if (v == null) {
            cm.dispose();
            return;
        }
        var r = UE.create(cm.getClient(), cm.getPlayer(), name.toString(), v[0], v[1], v[2] + v[3], v[4], v[5]);
        if (r == 0) {
            cm.sendOk("#b" + name + "#k, my Ultimate Explorer, has been born. You will find them on your character list. They await you here in Ereve.");
            cm.dispose();
        } else if (r == -1) {
            status = 0;
            cm.sendGetText(UE.CREATOR + "That name cannot be used. Please choose another.");
        } else if (r == -3) {
            cm.sendOk("You have no free character slot for an Ultimate Explorer.");
            cm.dispose();
        } else {
            cm.sendOk("Something went wrong. Please try again.");
            cm.dispose();
        }
        return;
    }
    cm.dispose();
}
