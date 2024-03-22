package com.github.ralgond.paxos.core.env;

import com.github.ralgond.paxos.core.common.PaxosPromised;
import com.github.ralgond.paxos.core.common.PaxosValue;

import java.util.Optional;

public interface PaxosPersistent {
    public void open(String path);

    public void close();

    public void savePromised(PaxosPromised promised);

    public Optional<PaxosPromised> getPromised(Long paxos_id);

    public Long incrSerialNumber();

    public void saveResult(Long paxos_id, PaxosValue paxos_value);

    /**
     *
     * @return -1 means there is not paxos_value stored in this machine.
     */
    public Long getMaxPaxosId();

    public void saveMaxPaxosId(Long paxosId);

    public void saveProposalId(Long proposalId);

    /**
     *
     * @return -1 means not any proposal id saved.
     */
    public Long getProposalId();
}
