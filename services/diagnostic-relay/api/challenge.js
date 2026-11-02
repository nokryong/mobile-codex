import {productionRelay} from '../lib/relay.mjs';
export default {fetch: request => productionRelay()(request, 'challenge')};
