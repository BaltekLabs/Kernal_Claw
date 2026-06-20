import React, { useState } from 'react';
import {
  Cpu,
  ShieldCheck,
  Wrench,
  ServerCog,
  Smartphone,
  Cable,
  CheckCircle2,
  ArrowRight,
  Factory,
  Bot,
  Lock,
  Activity,
  TerminalSquare
} from 'lucide-react';

const LEAD_ENDPOINT = import.meta.env.VITE_LEAD_ENDPOINT || '';
const FALLBACK_EMAIL = 'sales@balteksystems.com';

function App() {
  const [form, setForm] = useState({
    name: '',
    company: '',
    email: '',
    phone: '',
    role: '',
    useCase: '',
    timeline: '30-60 days'
  });

  const [status, setStatus] = useState('');

  const update = (field, value) => {
    setForm((prev) => ({ ...prev, [field]: value }));
  };

  const submitLead = async (event) => {
    event.preventDefault();
    setStatus('Submitting...');

    const payload = {
      ...form,
      source: 'Kernel Claw FieldOps Funnel',
      submittedAt: new Date().toISOString()
    };

    try {
      if (LEAD_ENDPOINT) {
        const res = await fetch(LEAD_ENDPOINT, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        });

        if (!res.ok) throw new Error('Lead endpoint failed');
        setStatus('Received. We will follow up to discuss a pilot.');
        setForm({
          name: '',
          company: '',
          email: '',
          phone: '',
          role: '',
          useCase: '',
          timeline: '30-60 days'
        });
        return;
      }

      const subject = encodeURIComponent('Kernel Claw FieldOps Pilot Request');
      const body = encodeURIComponent(
        `New pilot request\n\nName: ${form.name}\nCompany: ${form.company}\nEmail: ${form.email}\nPhone: ${form.phone}\nRole: ${form.role}\nTimeline: ${form.timeline}\n\nUse Case:\n${form.useCase}`
      );
      window.location.href = `mailto:${FALLBACK_EMAIL}?subject=${subject}&body=${body}`;
      setStatus('Opening your email client...');
    } catch (error) {
      setStatus('Something went wrong. Email sales@balteksystems.com directly.');
    }
  };

  return (
    <main className="page">
      <section className="hero">
        <nav className="nav">
          <div className="brand">
            <div className="brandMark">
              <Bot size={22} />
            </div>
            <span>Kernel Claw FieldOps</span>
          </div>
          <a className="navCta" href="#pilot">Request Pilot</a>
        </nav>

        <div className="heroGrid">
          <div className="heroCopy">
            <div className="eyebrow">
              <Factory size={16} />
              Industrial AI mobile command system
            </div>

            <h1>
              Put an AI troubleshooting console in the hands of every field tech.
            </h1>

            <p className="heroText">
              Kernel Claw FieldOps combines a hardened Android interface, local device context,
              approved command execution, and AI-assisted troubleshooting for controls, robotics,
              maintenance, and industrial support teams.
            </p>

            <div className="heroActions">
              <a className="primaryButton" href="#pilot">
                Build a paid pilot <ArrowRight size={18} />
              </a>
              <a className="secondaryButton" href="#system">
                See the system
              </a>
            </div>

            <div className="proofBar">
              <span><CheckCircle2 size={16} /> Mobile launcher interface</span>
              <span><CheckCircle2 size={16} /> Hardware + software package</span>
              <span><CheckCircle2 size={16} /> Human-approved actions</span>
            </div>
          </div>

          <div className="deviceCard" aria-label="Product mockup">
            <div className="deviceTop">
              <span></span><span></span><span></span>
            </div>
            <div className="deviceScreen">
              <div className="terminalHeader">
                <Activity size={15} /> FieldOps Live Context
              </div>
              <div className="signalRow">
                <span>PLC Line 4</span>
                <strong>Faulted</strong>
              </div>
              <div className="signalRow">
                <span>Drive Status</span>
                <strong>Overcurrent</strong>
              </div>
              <div className="signalRow">
                <span>Last Alarm</span>
                <strong>02:14 PM</strong>
              </div>
              <div className="assistantBox">
                <p>
                  Suggested path: verify load condition, inspect VFD fault history,
                  check motor leads, then run approved reset procedure.
                </p>
              </div>
              <button className="mockButton">Queue approved diagnostic</button>
            </div>
          </div>
        </div>
      </section>

      <section className="pain">
        <div className="sectionHeader">
          <p className="kicker">The problem</p>
          <h2>Industrial teams lose margin in the gap between fault, context, and action.</h2>
        </div>

        <div className="painGrid">
          <PainCard
            title="Tribal knowledge walks away"
            text="The best tech knows the machine, the program, the customer, and the weird fix. Everyone else burns hours rediscovering it."
          />
          <PainCard
            title="Docs are scattered"
            text="Manuals, PLC notes, screenshots, tickets, emails, and procedures live in different places, usually not where the technician needs them."
          />
          <PainCard
            title="Remote support is expensive"
            text="Senior engineers get dragged into repetitive troubleshooting instead of handling the high-value calls."
          />
        </div>
      </section>

      <section id="system" className="system">
        <div className="sectionHeader">
          <p className="kicker">The product</p>
          <h2>Hardware + software for controlled industrial AI assistance.</h2>
        </div>

        <div className="systemGrid">
          <Feature
            icon={<Smartphone />}
            title="Hardened mobile interface"
            text="Android launcher-style operator console for field techs, support teams, and plant-floor troubleshooting."
          />
          <Feature
            icon={<ServerCog />}
            title="AI routing layer"
            text="Routes requests between local logic, fast models, and full reasoning models to control latency and cost."
          />
          <Feature
            icon={<TerminalSquare />}
            title="Approved command bridge"
            text="Run diagnostic scripts, repo checks, logs, or support commands through controlled workflows instead of ad hoc guessing."
          />
          <Feature
            icon={<Cable />}
            title="Industrial workflow packs"
            text="Load machine procedures, customer standards, controls notes, fault trees, SOPs, and service playbooks."
          />
          <Feature
            icon={<ShieldCheck />}
            title="Human-in-the-loop safety"
            text="High-impact actions can require approval, logging, and review before execution."
          />
          <Feature
            icon={<Lock />}
            title="Private deployment options"
            text="Deploy as company-owned devices, private APK, managed tablets, or internal support tool."
          />
        </div>
      </section>

      <section className="offer">
        <div className="offerBox">
          <div>
            <p className="kicker">Pilot offer</p>
            <h2>Start with one machine, one team, one measurable workflow.</h2>
            <p>
              We configure Kernel Claw FieldOps around your existing troubleshooting process:
              procedures, alarm history, manuals, support notes, scripts, escalation paths,
              and technician workflows.
            </p>
          </div>

          <div className="offerList">
            <span><CheckCircle2 /> FieldOps mobile interface</span>
            <span><CheckCircle2 /> Device + software configuration</span>
            <span><CheckCircle2 /> One industrial workflow pack</span>
            <span><CheckCircle2 /> Pilot success review</span>
          </div>
        </div>
      </section>

      <section id="pilot" className="leadSection">
        <div className="leadCopy">
          <p className="kicker">Request access</p>
          <h2>Discuss a paid pilot for your field or controls team.</h2>
          <p>
            Best fit: controls integrators, robotics teams, industrial maintenance groups,
            field service companies, and technical support teams that need faster troubleshooting
            without turning AI loose unsupervised.
          </p>

          <div className="qualification">
            <Wrench size={20} />
            <span>
              Ideal first pilot: 3–10 technicians, one equipment family, one repeatable support workflow.
            </span>
          </div>
        </div>

        <form className="leadForm" onSubmit={submitLead}>
          <label>
            Name
            <input
              required
              value={form.name}
              onChange={(e) => update('name', e.target.value)}
              placeholder="Your name"
            />
          </label>

          <label>
            Company
            <input
              required
              value={form.company}
              onChange={(e) => update('company', e.target.value)}
              placeholder="Company name"
            />
          </label>

          <div className="twoCol">
            <label>
              Work email
              <input
                required
                type="email"
                value={form.email}
                onChange={(e) => update('email', e.target.value)}
                placeholder="you@company.com"
              />
            </label>

            <label>
              Phone
              <input
                value={form.phone}
                onChange={(e) => update('phone', e.target.value)}
                placeholder="Optional"
              />
            </label>
          </div>

          <div className="twoCol">
            <label>
              Role
              <input
                value={form.role}
                onChange={(e) => update('role', e.target.value)}
                placeholder="Controls Manager, Owner, etc."
              />
            </label>

            <label>
              Timeline
              <select value={form.timeline} onChange={(e) => update('timeline', e.target.value)}>
                <option>Now</option>
                <option>30-60 days</option>
                <option>60-90 days</option>
                <option>Exploring</option>
              </select>
            </label>
          </div>

          <label>
            What workflow would you want to improve first?
            <textarea
              required
              value={form.useCase}
              onChange={(e) => update('useCase', e.target.value)}
              placeholder="Example: troubleshooting VFD faults, robotics startup support, PLC/HMI issue triage, customer field service notes..."
            />
          </label>

          <button className="submitButton" type="submit">
            Request pilot discussion <ArrowRight size={18} />
          </button>

          {status && <p className="status">{status}</p>}

          <p className="finePrint">
            No generic chatbot demo. The pilot is built around your actual industrial workflow.
          </p>
        </form>
      </section>

      <footer className="footer">
        <div className="brand mini">
          <div className="brandMark">
            <Cpu size={18} />
          </div>
          <span>Kernel Claw FieldOps</span>
        </div>
        <p>Industrial AI interface system by Baltek Systems Services LLC.</p>
      </footer>
    </main>
  );
}

function PainCard({ title, text }) {
  return (
    <div className="painCard">
      <h3>{title}</h3>
      <p>{text}</p>
    </div>
  );
}

function Feature({ icon, title, text }) {
  return (
    <div className="feature">
      <div className="featureIcon">{icon}</div>
      <h3>{title}</h3>
      <p>{text}</p>
    </div>
  );
}

export default App;
