import Socket from "@/socket";

function findInvite(state, inviteId) {
  if (state.complexInvite && state.complexInvite.inviteWaiting && state.complexInvite.inviteWaiting.inviteId === inviteId) {
    console.log("Found complexInvite", state.complexInvite);
    return state.complexInvite.inviteWaiting;
  }
  if (state.inviteWaiting.inviteId === inviteId) {
    console.log("Found invite waiting", state.inviteWaiting);
    return state.inviteWaiting;
  } else {
    let result = state.invites.find(i => i.inviteId === inviteId);
    console.log("Found invite", result);
    return result;
  }
}

function emptyInvite() {
  return {
    dialogActive: false, // possibly unused
    inviteStep: 0,
    gameType: null,
    inviteId: null,
    cancelled: false,
    playersMin: 2,
    playersMax: 2,
    invitesSent: [] // { playerId, accepted }
  };
}

const lobbyStore = {
  namespaced: true,
  state: {
    yourPlayer: { name: "(UNKNOWN)", playerId: "UNKNOWN" },
    inviteWaiting: emptyInvite(),
    invites: [],
    lobby: {} // key: gameType, value: array of players (names)
  },
  getters: {},
  mutations: {
    setPlayer(state, player) {
      state.yourPlayer = {
        name: player.name,
        playerId: player.playerId
      }
    },
    inviteStep(state, step) {
      state.inviteWaiting.inviteStep = step
    },
    setInviteWaiting(state, e) {
      console.log("SET INVITE WAITING", state.inviteWaiting, e)
      state.inviteWaiting.inviteId = e.inviteId
      state.inviteWaiting.dialogActive = true
      state.inviteWaiting.invitesSent = [{ ...state.yourPlayer, status: true }];
      state.inviteWaiting.playersMin = e.playersMin;
      state.inviteWaiting.playersMax = e.playersMax;
      state.inviteWaiting.inviteStep = 2;
    },
    inviteStatus(state, data) {
      state.inviteWaiting.invitesSent.push({ playerId: data.playerId, status: null });
    },
    setLobbyUsers(state, data) {
        state.lobby = data;
    },
    inviteResponseReceived(state, e) {
      // This should only happen to the inviteWaiting at the moment
      let invite = findInvite(state, e.inviteId);
      invite.invitesSent.find(e => e.playerId === e.playerId && e.status === null).status = e.accepted ? true : false;
    },
    inviteReceived(state, e) {
      state.invites.push({ ...e, response: null });
    },
    cancelInvite(state, e) {
      // This can be either an invite recieved or the inviteWaiting
      let invite = findInvite(state, e.inviteId);
      invite.cancelled = true;
      invite.inviteStep = 0;
    },
    resetInviteWaiting(state) {
      state.inviteWaiting.inviteStep = 0;
      state.inviteWaiting = emptyInvite();
    },
    changeLobby(state, e) {
      // client, action, gameTypes
      let player = e.player; // { id, name }
      if (e.action === "joined") {
        let gameTypes = e.gameTypes;
        gameTypes.forEach(gt => {
          let list = state.lobby[gt];
          if (list == null) throw "No list for gameType " + gt;
        });
        gameTypes.map(gt => state.lobby[gt]).forEach(list => list.push(player));
      } else if (e.action === "left") {
        let gameTypes = Object.keys(state.lobby);
        gameTypes.map(gt => state.lobby[gt]).forEach(list => {
          let index = list.findIndex(e => e.id === player.id);
          if (index >= 0) {
            list.splice(index, 1);
          }
        });
      } else {
        throw "Unknown action: " + e.action;
      }
    },
    createInvite(state, gameType) {
      state.inviteWaiting.gameType = gameType;
      state.inviteWaiting.inviteStep = 1;
    }
  },
  actions: {
    cancelInvite(context) {
      let inviteId = context.state.inviteWaiting.inviteId
      Socket.route(`invites/${inviteId}/cancel`, {});
      context.commit("cancelInvite", { inviteId: inviteId });
    },
    createServerInvite(context) {
      console.log(context);
      Socket.route("invites/start", { gameType: context.state.inviteWaiting.gameType });
    },
    onSocketMessage(context, data) {
      if (data.type == "Lobby") {
        context.commit("setLobbyUsers", data.users);
      }
      if (data.type === "LobbyChange") {
        context.commit("changeLobby", data);
      }

      if (data.type == "InviteWaiting") {
        context.commit("setInviteWaiting", data);
      }
      if (data.type == "InviteStatus") {
        context.commit("inviteStatus", data);
      }
      if (data.type == "InviteResponse") {
        context.commit("inviteResponseReceived", data);
      }
      if (data.type == "InviteCancelled") {
        context.commit("cancelInvite", data);
      }
      if (data.type == "Invite") {
        context.commit("inviteReceived", data);
      }
      if (data.type == "GameStarted") {
        context.commit("resetInviteWaiting");
      }
    }
  }
};

export default lobbyStore;
