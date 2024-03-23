package com.github.ralgond.paxos.core.component;

import java.util.HashMap;
import java.util.TreeMap;

import com.github.ralgond.paxos.core.common.PaxosValue;
import com.github.ralgond.paxos.core.env.PaxosEnvironment;
import com.github.ralgond.paxos.core.env.PaxosTimerManager;
import com.github.ralgond.paxos.core.protocol.PaxosAcceptRequest;
import com.github.ralgond.paxos.core.protocol.PaxosAcceptResponse;
import com.github.ralgond.paxos.core.protocol.PaxosPrepareRequest;
import com.github.ralgond.paxos.core.protocol.PaxosPrepareResponse;

public class Proposer {
    public static class PaxosStateMachine implements PaxosTimerManager.Timer {
        final PaxosValue paxos_value;

        boolean stopped;

        boolean preparing;

        Long paxos_id;


        Long proposal_id;

        PaxosValue proposal_value;

        TreeMap<Integer, PaxosPrepareResponse> prepare_resp_map;

        TreeMap<Integer, PaxosPrepareResponse> notpromised_prepare_resp_map;

        TreeMap<Integer, PaxosAcceptResponse> accept_resp_map;

        TreeMap<Integer, PaxosAcceptResponse> notaccepted_accept_resp_map;

        public PaxosStateMachine(PaxosValue paxos_value) {
            this.paxos_value = paxos_value;
            this.stopped = false;
            this.preparing = true;
            this.prepare_resp_map = new TreeMap<>();
            this.accept_resp_map = new TreeMap<>();

            this.notpromised_prepare_resp_map = new TreeMap<>();
            this.notaccepted_accept_resp_map = new TreeMap<>();
        }

        @Override
        public Long id() {
            return this.paxos_value.value_sn;
        }

        public void start(PaxosEnvironment env) {
            /*
             * Choose a new proposal number n
             */
            this.proposal_id =  env.persistent.getProposalIdOnProposer() + 1;
            this.proposal_value = this.paxos_value;

            /*
             * Broadcast Prepare(n) to all servers
             */
            for (var server_id : env.config.getServers().keySet()) {
                PaxosPrepareRequest req = new PaxosPrepareRequest(
                        server_id,
                        this.proposal_id
                );
                env.sender.sendPrepareRequest(req);
            }

            this.preparing = true;
            env.timer_manager.removeTimer(this);
            env.timer_manager.addTimer(this, 3000L);
        }

        public void stop(PaxosEnvironment env) {
            assert (this.proposal_value.equals(this.paxos_value));
            this.stopped = true;
            env.timer_manager.removeTimer(this);
        }

        boolean isStopped() {
            return stopped;
        }

        boolean isPreparing() {
            return preparing;
        }

        public void onTimeout(PaxosEnvironment env) {
            this.start(env);
        }

        public void onRecvPrepareResponse(PaxosPrepareResponse resp, PaxosEnvironment env) {
            if (!this.isPreparing())
                return;

            if (!env.config.getServers().containsKey(resp.server_id))
                return;

            if (!resp.proposal_id.equals(this.proposal_id))
                return;

            if (!resp.isPromised()) {
                this.notpromised_prepare_resp_map.put(resp.server_id, resp);
                if (this.notpromised_prepare_resp_map.size() >= env.config.getServers().size() / 2 + 1) {
                    Long max_promised_id = -1L;
                    for (var prepare_resp : this.notpromised_prepare_resp_map.values()) {
                        if (prepare_resp.accepted.proposal > max_promised_id) {
                            max_promised_id = prepare_resp.accepted.proposal;
                        }
                    }

                    env.persistent.saveProposalIdOnProposer(max_promised_id);
                    this.start(env);
                }
                return;
            }

            this.prepare_resp_map.put(resp.server_id, resp);

            if (this.prepare_resp_map.size() >= env.config.getServers().size() / 2 + 1) {
                Long max_promised_id = this.proposal_id;
                PaxosValue max_promised_value = this.paxos_value;

                /*
                 * When responses received from majority:
                 * if any acceptedValues returned, replace value with acceptedValue for
                 * highest acceptedProposal.
                 */
                for (var prepare_resp : this.prepare_resp_map.values()) {
                    if (prepare_resp.accepted.proposal > max_promised_id) {
                        max_promised_id = prepare_resp.accepted.proposal;
                        max_promised_value = prepare_resp.accepted.value;
                    }
                }

                env.persistent.saveProposalIdOnProposer(max_promised_id);
                this.proposal_id = max_promised_id;
                this.proposal_value = max_promised_value;

                /*
                 * Broadcast Accept(n, value) to all servers.
                 */
                for (var server_id : env.config.getServers().keySet()) {
                    var req = new PaxosAcceptRequest(server_id,
                            max_promised_id,
                            max_promised_value);
                    env.sender.sendAcceptRequest(req);
                }

                this.preparing = false;
            }
        }
        public void onRecvAcceptResponse(PaxosAcceptResponse resp, PaxosEnvironment env) {
            if (this.isPreparing())
                return;

            if (!env.config.getServers().containsKey(resp.server_id))
                return;

            if (!resp.proposal_id.equals(this.proposal_id))
                return;

            if (!resp.isAccepted()) {
                this.notaccepted_accept_resp_map.put(resp.server_id, resp);
                if (this.notaccepted_accept_resp_map.size() >= env.config.getServers().size() / 2 + 1) {
                    this.start(env);
                }
                return;
            }

            this.accept_resp_map.put(resp.server_id, resp);

            if (this.accept_resp_map.size() >= env.config.getServers().size() / 2 + 1) {
                if (this.proposal_value.equals(this.paxos_value)) {
                    this.stop(env);
                } else {
                    this.start(env);
                }
            }
        }
    }


    public PaxosEnvironment env;
    public PaxosStateMachine state_machine;

    public void start(PaxosEnvironment env, String value) {
        this.env = env;
        var paxos_value = new PaxosValue(env.config.getServerId(),
                env.persistent.incrSerialNumber(),
                value);

        this.state_machine = new PaxosStateMachine(paxos_value);
        this.state_machine.start(env);
    }
}
