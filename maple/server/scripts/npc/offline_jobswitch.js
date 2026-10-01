/*
 * Offline: the Job Switch Token (item 2002032). Pick another job at the same advancement; level and EXP
 * stay, SP and AP are given back, gear the new job cannot wear is taken off. The token is used up on
 * a switch, kept on cancel.
 */
const JobSwitch = Java.type('offline.OfflineJobSwitch');
const TOKEN = 2002032;
var status = -1;
var choices;

function start() {
    choices = JobSwitch.choices(cm.getPlayer());
    if (choices.length == 0) {
        cm.sendOk("There is no other job you can switch to right now.\r\n#b(Beginners advance first; Aran has a single path.)#k");
        cm.dispose();
        return;
    }
    var text = "Which job would you like to become? Your level and EXP stay. All your SP and AP will be given back to spend again, and gear your new job cannot wear will be taken off.#b";
    for (var i = 0; i < choices.length; i++) text += "\r\n#L" + i + "#" + cm.getJobName(choices[i]) + "#l";
    cm.sendSimple(text);
}

function action(mode, type, selection) {
    if (mode != 1 || selection < 0 || selection >= choices.length) {
        cm.dispose();
        return;
    }
    if (!cm.haveItem(TOKEN)) {
        cm.dispose();
        return;
    }
    if (JobSwitch.apply(cm.getPlayer(), choices[selection])) {
        cm.gainItem(TOKEN, -1);
        cm.sendOk("You are now a #b" + cm.getJobName(choices[selection]) + "#k! Spend your AP and SP as you like.");
    }
    cm.dispose();
}
