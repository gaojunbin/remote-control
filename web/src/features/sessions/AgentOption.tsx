import { agentLabel, agentMark } from '../../strings';

/**
 * One agent in a list that has room for its name: the mark, then the name.
 * `docs/DESIGN.md` § "Agents" — the mark is what the agent filter and the
 * new-session form show, and the name follows it wherever a line is wide
 * enough to carry both.
 */
export function AgentOption({ agent }: { agent: string }) {
  return (
    <span className="agent-option">
      <span className="agent-mark" aria-hidden>
        {agentMark(agent)}
      </span>
      {agentLabel(agent)}
    </span>
  );
}
