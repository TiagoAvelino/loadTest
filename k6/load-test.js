import { check } from 'k6';
import http from 'k6/http';

export const options = {
    scenarios: {
        my_scenario1: {
            executor: 'constant-arrival-rate',
            duration: '120s', // total duration
            preAllocatedVUs: 25, // to allocate runtime resources

            rate: 5000, // number of constant iterations given `timeUnit`
            timeUnit: '1s',
        },
    },
};

export default function () {
    const payload = JSON.stringify({
        "message": "testew",
        "jwt": "teste"
     });
    const headers = { 'Content-Type': 'application/json','Accept':'*/*' };
    const res = http.post('http://mqtt-producer-mqtt.apps.cluster-mb9p8.mb9p8.sandbox3022.opentlc.com/mqtt/send?topic=mqtt-message-in/1/2/app/test', payload, { headers });

    check(res, {
        'Post status is 200': (r) => res.status === 200
    });
}