let gamejs = require("../../games-js/web/games-js");
if (typeof gamejs["games-js"] !== "undefined") {
  // This is needed when doing a production build, but is not used for `npm run dev` locally.
  gamejs = gamejs["games-js"];
}
/*
Edit just one file instead of editing all the following files:
- route/index.js
- StartScreen.vue
- Invites.vue
- store.js
- etc...
*/

import PlayGame from "@/components/PlayGame";

import RoyalGameOfUR from "@/components/RoyalGameOfUR";
import UTTT from "@/components/games/UTTT";
import ECSGame from "@/components/ecs/ECSGame";
import DSLTTT from "@/components/games/DSLTTT";
import TTT3D from "@/components/games/TTT3D";

import DslGameState from "./components/games/DslGameState";
const defaultRouteProps = route => ({
    gameInfo: route.params.gameInfo,
    showRules: true
})

const tttActions = {
    play(data) {
        return data.x + ',' + data.y
    }
}

const supportedGames = {
    "DSL-UR": {
        displayName: "Royal Game of UR",
        dsl: new gamejs.net.zomis.games.dsl.DslUR().gameUR,
        actions: {
            roll: () => "roll",
            move: (i) => `${i}`
        },
        component: RoyalGameOfUR,
        routeProps: defaultRouteProps
    },
    "DSL-Connect4": {
        displayName: "Connect Four",
        dsl: new gamejs.net.zomis.games.dsl.DslTTT().gameConnect4,
        actions: tttActions,
        component: DSLTTT,
        routeProps: defaultRouteProps
    },
    "Quixo": {
        displayName: "Quixo",
        dsl: new gamejs.net.zomis.games.dsl.DslTTT().gameQuixo,
        actions: tttActions,
        component: DSLTTT,
        routeProps: defaultRouteProps
    },
    "DSL-Reversi": {
        displayName: "Reversi",
        dsl: new gamejs.net.zomis.games.dsl.DslTTT().gameReversi,
        actions: tttActions,
        component: DSLTTT,
        routeProps: defaultRouteProps
    },
    "DSL-TTT3D": {
        displayName: "3D Tic-Tac-Toe / Connect Four",
        dsl: new gamejs.net.zomis.games.dsl.DslTTT3D().game,
        component: TTT3D,
        routeProps: defaultRouteProps
    },
    "DSL-UTTT": {
        displayName: "Tic-Tac-Toe Ultimate",
        dsl: new gamejs.net.zomis.games.dsl.DslTTT().gameUTTT,
        actions: tttActions,
        component: UTTT,
        routeProps: defaultRouteProps
    },
    "UTTT-ECS": {
        enabled: false,
        dsl: false,
        store: null,
        component: ECSGame,
        routeProps: defaultRouteProps
    },
    "DSL-TTT": {
        displayName: "Tic-Tac-Toe",
        dsl: new gamejs.net.zomis.games.dsl.DslTTT().game,
        actions: tttActions,
        component: DSLTTT,
        routeProps: defaultRouteProps
    }
}

function enabledGames() {
    return Object.keys(supportedGames).filter(key => supportedGames[key].enabled !== false)
}

export default {
    gamejs: gamejs,
    storeModules() {
        let modules = {}
        for (let key of enabledGames()) {
            let game = supportedGames[key]
            if (game.dsl) {
                modules["DslGameState"] = DslGameState
            } else if (game.store) {
                modules[key] = game.store
            }
        }
        return modules
    },
    routes() {
        return enabledGames().map(key => {
            let game = supportedGames[key]
            return {
                path: `/games/${key}/:gameId`,
                name: game.routeName || key,
                component: PlayGame,
                props: route => ({
                    ...game.routeProps(route)
                })
            }
        })
    },
    components() {
        let components = {}
        for (let key of enabledGames()) {
            let game = supportedGames[key]
            let componentName = game.component.name
            components[componentName] = game.component
        }
        return components
    },
    stateModules(state) {
        let modules = this.storeModules()
        return Object.keys(modules).map(moduleName => state[moduleName]);
    },
    enabledGameKeys() {
        return enabledGames()
    },
    games: supportedGames
}
