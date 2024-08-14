import { check } from 'k6';
import http from 'k6/http';

export const options = {
    scenarios: {
        my_scenario1: {
            executor: 'constant-arrival-rate',
            duration: '120s', // total duration
            preAllocatedVUs: 25, // to allocate runtime resources

            rate: 50, // number of constant iterations given `timeUnit`
            timeUnit: '1s',
        },
    },
};

var i = 0;
export default function () {
    i = i+1;
    const payload = JSON.stringify({
        "message": "teste" + i,
        "jwt": "teste"
     });
    const headers = { 'Content-Type': 'application/json','Accept':'*/*' };
    const res = http.post('http://mqtt-producer-mqtt.apps.cluster-ptpbn.sandbox133.opentlc.com/mqtt/send?topic=mqtt-message-in/1/2/app/test', payload, { headers });

    check(res, {
        'Post status is 200': (r) => res.status === 200
    });
}