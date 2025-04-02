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
        const randomNum = Math.floor(Math.random() * 50001);
        const headers = { 'Content-Type': 'application/json','Accept':'*/*' };
        const url = `http://mqtt-producer-kafka.apps.tiago-cluster.sandbox2300.opentlc.com/mqtt/send?topic=mqtt-message-in/${randomNum}/2/app/test`;
        const res = http.post(url, payload, { headers });

        check(res, {
            'Post status is 200': (r) => res.status === 200
        });
    }

//curl -X POST \
// http://localhost:8080/mqtt/send?topic=mqtt-message-in/1/2/app/test \
// -H "Content-Type: application/json" \
// -H "Accept: */*" \
// -d '{
//       "message": "teste1",
//       "jwt": "teste"
//     }'


// curl -X POST "http://mqtt-producer-horus.apps.tiago-cluster.sandbox3073.opentlc.com/mqtt/send?topic=mqtt-message-in/1/2/app/test" \
// -H "Content-Type: application/json" \
// -H "Accept: */*" \
// -d '{"message": "teste1", "jwt": "teste"}'
// curl -X POST "http://mqtt-producer-kafka.apps.tiago-cluster.sandbox2300.opentlc.com/mqtt/send?topic=mqtt-message-in/1/2/app/test" \
// -H "Content-Type: application/json" \
// -H "Accept: */*" \
// -d '{"message": "teste1", "jwt": "teste"}'